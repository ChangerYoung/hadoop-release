/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.hdfs.server.datanode;

import static org.apache.hadoop.fs.CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import org.apache.commons.daemon.Daemon;
import org.apache.commons.daemon.DaemonContext;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.server.common.HdfsConstants;
import org.apache.hadoop.security.SecurityUtil;
import org.mortbay.jetty.Connector;

/**
 * Utility class to start a datanode in a secure cluster, first obtaining 
 * privileged resources before main startup and handing them to the datanode.
 */
public class SecureDataNodeStarter implements Daemon {
  /**
   * Stash necessary resources needed for datanode operation in a secure env.
   */
  public static class SecureResources {
    private final ServerSocket streamingSocket;
    private final Connector listener;
    public SecureResources(ServerSocket streamingSocket,
        Connector listener) {

      this.streamingSocket = streamingSocket;
      this.listener = listener;
    }

    public ServerSocket getStreamingSocket() { return streamingSocket; }

    public Connector getListener() { return listener; }
  }
  
  private String [] args;
  private SecureResources resources;
  
  @Override
  public void init(DaemonContext context) throws Exception {
    System.err.println("Initializing secure datanode resources");
    // We should only start up a secure datanode in a Kerberos-secured cluster
    Configuration conf = new Configuration(); // Skip UGI method to not log in
    if(!conf.get(HADOOP_SECURITY_AUTHENTICATION).equals("kerberos"))
      throw new RuntimeException("Cannot start secure datanode in unsecure cluster");
    
    // Stash command-line arguments for regular datanode
    args = context.getArguments();
    
    // Obtain secure port for data streaming to datanode
    InetSocketAddress socAddr = DataNode.getStreamingAddr(conf);
    int socketWriteTimeout = conf.getInt("dfs.datanode.socket.write.timeout",
        HdfsConstants.WRITE_TIMEOUT);
    
    ServerSocket ss = (socketWriteTimeout > 0) ? 
        ServerSocketChannel.open().socket() : new ServerSocket();
    ss.bind(socAddr, 0);
    
    // Check that we got the port we need
    if(ss.getLocalPort() != socAddr.getPort())
      throw new RuntimeException("Unable to bind on specified streaming port in secure " +
      		"context. Needed " + socAddr.getPort() + ", got " + ss.getLocalPort());

    Connector listener = null;

    if (!conf.getBoolean("dfs.internal.datanode.https-only", false)) {
      // Obtain secure listener for web server
      listener = SecurityUtil.openListener(conf);

      InetSocketAddress infoSocAddr = DataNode.getInfoAddr(conf);
      listener.setHost(infoSocAddr.getHostName());
      listener.setPort(infoSocAddr.getPort());
      // Open listener here in order to bind to port as root
      listener.open();
      if(listener.getPort() != infoSocAddr.getPort())
        throw new RuntimeException("Unable to bind on specified info port in secure " +
          "context. Needed " + socAddr.getPort() + ", got " + ss.getLocalPort());
   
      if(ss.getLocalPort() >= 1023 || listener.getPort() >= 1023)
        throw new RuntimeException("Cannot start secure datanode on non-privileged "
          +" ports. (streaming port = " + ss + " ) (http listener port = " +
          listener.getConnection() + "). Exiting.");
 
      System.err.println("Successfully obtained privileged resources (streaming port = "
          + ss + " ) (http listener port = " + listener.getConnection() +")");
    } else {
      // XXX: Apple specific hack for disabling HTTP in datanode in secure setups
      if (!conf.getBoolean("dfs.https.enable", false)) {
    	throw new RuntimeException("dfs.https.enable has to be true if dfs.internal.datanode.https-only equals to true");
      }
    }
    resources = new SecureResources(ss, listener);
  }

  @Override
  public void start() throws Exception {
    System.err.println("Starting regular datanode initialization");
    DataNode.secureMain(args, resources);
  }
  
  @Override public void destroy() { /* Nothing to do */ }
  @Override public void stop() throws Exception { /* Nothing to do */ }
}
