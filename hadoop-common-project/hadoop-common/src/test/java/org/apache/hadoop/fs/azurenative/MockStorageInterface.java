package org.apache.hadoop.fs.azurenative;

import java.io.*;
import java.net.*;
import java.util.*;

import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.util.URIUtil;
import org.apache.commons.io.output.ByteArrayOutputStream;




import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import com.microsoft.windowsazure.storage.*;
import com.microsoft.windowsazure.storage.blob.*;

/**
 * A mock implementation of the Azure Storage interaction
 * layer for unit tests. Just does in-memory storage.
 */
public class MockStorageInterface extends StorageInterface {
  private InMemoryBlockBlobStore backingStore;
  private final ArrayList<PreExistingContainer> preExistingContainers =
      new ArrayList<MockStorageInterface.PreExistingContainer>();
  private String baseUriString;
  
  public InMemoryBlockBlobStore getBackingStore() {
    return backingStore;
  }

  /**
   * Mocks the situation where a container already exists before WASB
   * comes in, i.e. the situation where a user creates a container then
   * mounts WASB on the pre-existing container.
   * @param uri The URI of the container.
   * @param metadata The metadata on the container.
   */
  public void addPreExistingContainer(String uri,
      HashMap<String, String> metadata) {
    preExistingContainers.add(new PreExistingContainer(uri, metadata));
  }
  
  @Override
  public void setRetryPolicyFactory(final RetryPolicyFactory retryPolicyFactory) {
  }

  @Override
  public void setTimeoutInMs(int timeoutInMs) {
  }

  @Override
  public void createBlobClient(CloudStorageAccount account) {
    backingStore = new InMemoryBlockBlobStore();
  }

  @Override
  public void createBlobClient(URI baseUri) {
    backingStore = new InMemoryBlockBlobStore();
  }

  @Override
  public void createBlobClient(URI baseUri, StorageCredentials credentials) {
    this.baseUriString = baseUri.toString();
    backingStore = new InMemoryBlockBlobStore();
  }
  
  @Override
  public StorageCredentials getCredentials() {
    // Not implemented for mock interface.
    return null;
  }


  @Override
  public CloudBlobContainerWrapper getContainerReference(String name)
      throws URISyntaxException, StorageException {
    String fullUri;
    try {
      fullUri = baseUriString + "/" + URIUtil.encodePath(name);
    }
    catch (URIException e){
      throw new RuntimeException("problem encoding fullUri", e);
    }
    
    MockCloudBlobContainerWrapper container = new MockCloudBlobContainerWrapper(fullUri, name);
    // Check if we have a pre-existing container with that name, and prime
    // the wrapper with that knowledge if it's found.
    for (PreExistingContainer existing : preExistingContainers) {
      if (fullUri.equalsIgnoreCase(existing.containerUri)) {
        // We have a pre-existing container. Mark the wrapper as created and
        // make sure we use the metadata for it.
        container.created = true;
        backingStore.setContainerMetadata(existing.containerMetadata);
        break;
      }
    }
    return container;
  }

  class MockCloudBlobContainerWrapper extends CloudBlobContainerWrapper {
    private boolean created = false;
    private HashMap<String, String> metadata;
    private final String baseUri;
    private final String name;

    public MockCloudBlobContainerWrapper(String baseUri, String name) {
      this.baseUri = baseUri;
      this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean exists(OperationContext opContext) throws StorageException {
      return created;
    }

    @Override
    public void create(OperationContext opContext) throws StorageException {
      created = true;
      backingStore.setContainerMetadata(metadata);
    }

    @Override
    public HashMap<String, String> getMetadata() {
      return metadata;
    }

    @Override
    public void setMetadata(HashMap<String, String> metadata) {
      this.metadata = metadata;
    }

    @Override
    public void downloadAttributes(OperationContext opContext)
        throws StorageException {
      metadata = backingStore.getContainerMetadata();
    }

    @Override
    public void uploadMetadata(OperationContext opContext)
        throws StorageException {
      backingStore.setContainerMetadata(metadata);
    }
    
    @Override
    public CloudBlobDirectoryWrapper getDirectoryReference(String relativePath)
        throws URISyntaxException, StorageException {
      return new MockCloudBlobDirectoryWrapper(new URI(fullUriString(relativePath, true)));
    }

    
    @Override
    public CloudBlockBlobWrapper getBlockBlobReference(String relativePath)
        throws URISyntaxException, StorageException {
      return new MockCloudBlockBlobWrapper(new URI(fullUriString(relativePath, false)), null, 0);
    }
    
    @Override
    public CloudPageBlobWrapper getPageBlobReference(String blobAddressUri)
        throws URISyntaxException, StorageException {
      return new MockCloudPageBlobWrapper(new URI(blobAddressUri), null, 0);
    }
    
    //helper to create full URIs for directory and blob. 
    //use withTrailingSlash=true to get a good path for a directory. 
    private String fullUriString(String relativePath, boolean withTrailingSlash){
      String fullUri;
      
      String baseUri = this.baseUri;
      if(!baseUri.endsWith("/")){
        baseUri += "/"; 
      }
      if(withTrailingSlash && !relativePath.equals("") && !relativePath.endsWith("/")){
        relativePath += "/"; 
      }  

      try {
        fullUri = baseUri + URIUtil.encodePath(relativePath);
      }
      catch (URIException e){
        throw new RuntimeException("problem encoding fullUri", e);
      }
      
      return fullUri;
    }
  }
  
  private static class PreExistingContainer {
    final String containerUri;
    final HashMap<String, String> containerMetadata;

    public PreExistingContainer(String uri,
        HashMap<String, String> metadata) {
      this.containerUri = uri;
      this.containerMetadata = metadata;
    }
  }

  class MockCloudBlobDirectoryWrapper extends CloudBlobDirectoryWrapper {
    private URI uri;

    public MockCloudBlobDirectoryWrapper(URI uri) {
      this.uri = uri;
    }

    @Override
    public CloudBlobContainer getContainer() throws URISyntaxException,
        StorageException {
      return null;
    }

    @Override
    public CloudBlobDirectory getParent() throws URISyntaxException,
        StorageException {
      return null;
    }

    @Override
    public URI getUri() {
      return uri;
    }

    @Override
    public Iterable<ListBlobItem> listBlobs(String prefix,
        boolean useFlatBlobListing, EnumSet<BlobListingDetails> listingDetails,
        BlobRequestOptions options, OperationContext opContext)
        throws URISyntaxException, StorageException {
      ArrayList<ListBlobItem> ret = new ArrayList<ListBlobItem>();
      String fullPrefix = prefix == null ?
          uri.toString() :
          new URI(
              uri.getScheme(),
              uri.getAuthority(),
              uri.getPath() + prefix,
              uri.getQuery(),
              uri.getFragment()).toString();
      boolean includeMetadata = listingDetails.contains(BlobListingDetails.METADATA);
      HashSet<String> addedDirectories = new HashSet<String>();
      for (InMemoryBlockBlobStore.ListBlobEntry current : backingStore.listBlobs(
          fullPrefix, includeMetadata)) {
        int indexOfSlash = current.getKey().indexOf('/', fullPrefix.length());
        if (useFlatBlobListing || indexOfSlash < 0) {
          if (current.isPageBlob()) {
            ret.add(new MockCloudPageBlobWrapper(
                new URI(current.getKey()),
                current.getMetadata(),
                current.getContentLength()));
          } else {
          ret.add(new MockCloudBlockBlobWrapper(
              new URI(current.getKey()),
              current.getMetadata(),
              current.getContentLength()));
          }
        } else {
          String directoryName = current.getKey().substring(0, indexOfSlash);
          if (!addedDirectories.contains(directoryName)) {
            addedDirectories.add(current.getKey());
            ret.add(new MockCloudBlobDirectoryWrapper(new URI(
                directoryName + "/")));
          }
        }
      }
      return ret;
    }

    @Override
    public StorageUri getStorageUri() {
      throw new NotImplementedException();
    }
  }
  
  abstract class MockCloudBlobWrapper implements CloudBlobWrapper {
    protected final URI uri;
    protected HashMap<String, String> metadata =
        new HashMap<String, String>();
    protected BlobProperties properties;

    protected MockCloudBlobWrapper(URI uri, HashMap<String, String> metadata,
        int length) {
      this.uri = uri;
      this.metadata = metadata;
      this.properties = new BlobProperties();
      this.properties.setLength(length);
      this.properties.setLastModified(
          Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime());
    }

    protected void refreshProperties(boolean getMetadata) {
      if (backingStore.exists(uri.toString())) {
        byte[] content = backingStore.getContent(uri.toString());
        properties = new BlobProperties();
        properties.setLength(content.length);
        properties.setLastModified(
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime());
        if (getMetadata) {
          metadata = backingStore.getMetadata(uri.toString());
        }
      }
    }

    @Override
    public CloudBlobContainer getContainer() throws URISyntaxException,
        StorageException {
      return null;
    }

    @Override
    public CloudBlobDirectory getParent() throws URISyntaxException,
        StorageException {
      return null;
    }

    @Override
    public URI getUri() {
      return uri;
    }

    @Override
    public HashMap<String, String> getMetadata() {
      return metadata;
    }

    @Override
    public void setMetadata(HashMap<String, String> metadata) {
      this.metadata = metadata;
    }

    @Override
    public void startCopyFromBlob(CloudBlobWrapper sourceBlob,
        OperationContext opContext) throws StorageException, URISyntaxException {
      backingStore.copy(sourceBlob.getUri().toString(), uri.toString());
      //TODO: set the backingStore.properties.CopyState and 
      //      update azureNativeFileSystemStore.waitForCopyToComplete
    }
    
    @Override
    public CopyState getCopyState() {
       return this.properties.getCopyState();
    }
    
    @Override
    public void delete(OperationContext opContext) throws StorageException {
      backingStore.delete(uri.toString());
    }

    @Override
    public boolean exists(OperationContext opContext) throws StorageException {
      return backingStore.exists(uri.toString());
    }

    @Override
    public void downloadAttributes(OperationContext opContext)
        throws StorageException {
      refreshProperties(true);
    }

    @Override
    public BlobProperties getProperties() {
      return properties;
    }

    @Override
    public InputStream openInputStream(BlobRequestOptions options,
        OperationContext opContext) throws StorageException {
      return new ByteArrayInputStream(backingStore.getContent(uri.toString()));
    }

    @Override
    public void uploadMetadata(OperationContext opContext)
        throws StorageException {
      backingStore.setMetadata(uri.toString(), metadata);
    }
    
    @Override
    public void downloadRange(long offset, long length, OutputStream os,
        BlobRequestOptions options, OperationContext opContext)
        throws StorageException {
      throw new NotImplementedException();
    }
  }

  class MockCloudBlockBlobWrapper extends MockCloudBlobWrapper
    implements CloudBlockBlobWrapper {
    public MockCloudBlockBlobWrapper(URI uri, HashMap<String, String> metadata,
        int length) {
      super(uri, metadata, length);
    }

    @Override
    public OutputStream openOutputStream(BlobRequestOptions options,
        OperationContext opContext) throws StorageException {
      return backingStore.uploadBlockBlob(uri.toString(), metadata);
    }

    @Override
    public void setStreamMinimumReadSizeInBytes(int minimumReadSizeBytes) {  
    }

    @Override
    public void setWriteBlockSizeInBytes(int writeBlockSizeBytes) {  
    }

    @Override
    public StorageUri getStorageUri() {
      return null;
    }
    
    @Override 
    public void uploadProperties(OperationContext context) {
    }
  }

  class MockCloudPageBlobWrapper extends MockCloudBlobWrapper
    implements CloudPageBlobWrapper {
    public MockCloudPageBlobWrapper(URI uri, HashMap<String, String> metadata,
        int length) {
      super(uri, metadata, length);
    }

    @Override
    public void create(long length, BlobRequestOptions options,
        OperationContext opContext) throws StorageException {
      throw new NotImplementedException();
    }

    @Override
    public void uploadPages(InputStream sourceStream, long offset, long length,
        BlobRequestOptions options, OperationContext opContext)
        throws StorageException, IOException {
      throw new NotImplementedException();
    }

    @Override
    public ArrayList<PageRange> downloadPageRanges(BlobRequestOptions options,
        OperationContext opContext) throws StorageException {
      throw new NotImplementedException();
    }
    
    @Override
    public void setStreamMinimumReadSizeInBytes(int minimumReadSize) {
    }

    @Override
    public void setWriteBlockSizeInBytes(int writeBlockSizeInBytes) {
    }

    @Override
    public StorageUri getStorageUri() {
        throw new NotImplementedException();
    }

    @Override
    public void uploadProperties(OperationContext opContext)
        throws StorageException {
      // TODO Auto-generated method stub
      
    }
  }
}
