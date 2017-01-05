/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.azure;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;

/**
 *  Interface to implement authorization support in WASB.
 *  API's of this interface will be implemented in the
 *  StorageInterface Layer before making calls to Azure
 *  Storage.
 */
public interface WasbAuthorizerInterface {
  /**
   * Initializer method
   * @param conf - Configuration object
   * @return True - If initialization successful
   *         False - Otherwise
   */
  public void init(Configuration conf)
      throws WasbAuthorizationException, IOException;

  /**
   * Authorizer API to authorize access in WASB.
   * @param absolute : Absolute WASB Path used for access.
   * @param accessType : Type of access
   * @param delegationToken : The user information.
   * @return : true - If access allowed false - If access is not allowed.
   */
  public boolean authorize(String wasbAbolutePath, String accessType,
      String delegationToken) throws WasbAuthorizationException, IOException;
}