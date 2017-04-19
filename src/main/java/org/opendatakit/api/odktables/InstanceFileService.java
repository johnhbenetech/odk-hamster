/*
 * Copyright (C) 2012-2013 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.api.odktables;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.glassfish.jersey.media.multipart.BodyPart;
import org.glassfish.jersey.media.multipart.ContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.opendatakit.aggregate.odktables.rest.ApiConstants;
import org.opendatakit.aggregate.odktables.rest.entity.OdkTablesFileManifest;
import org.opendatakit.aggregate.odktables.rest.entity.OdkTablesFileManifestEntry;
import org.opendatakit.constants.BasicConsts;
import org.opendatakit.constants.ServletConsts;
import org.opendatakit.context.CallingContext;
import org.opendatakit.odktables.FileContentInfo;
import org.opendatakit.odktables.InstanceFileChangeDetail;
import org.opendatakit.odktables.InstanceFileManager;
import org.opendatakit.odktables.InstanceFileManager.FetchBlobHandler;
import org.opendatakit.odktables.InstanceFileManager.FileContentHandler;
import org.opendatakit.odktables.exception.ODKTablesException;
import org.opendatakit.odktables.exception.PermissionDeniedException;
import org.opendatakit.odktables.relation.DbTableInstanceManifestETags;
import org.opendatakit.odktables.relation.DbTableInstanceManifestETags.DbTableInstanceManifestETagEntity;
import org.opendatakit.odktables.security.TablesUserPermissions;
import org.opendatakit.persistence.PersistenceUtils;
import org.opendatakit.persistence.exception.ODKDatastoreException;
import org.opendatakit.persistence.exception.ODKEntityNotFoundException;
import org.opendatakit.persistence.exception.ODKTaskLockException;

public class InstanceFileService {

  /**
   * The url of the servlet that for downloading and uploading files. This must be appended to the
   * odk table service.
   */
  public static final String SERVLET_PATH = "files";

  public static final String PARAM_AS_ATTACHMENT = "as_attachment";
  public static final String ERROR_MSG_INVALID_ROW_ID = "Invalid RowId.";
  public static final String ERROR_MSG_MULTIPART_MESSAGE_EXPECTED = "Multipart Form expected.";
  public static final String ERROR_MSG_MULTIPART_FILES_ONLY_EXPECTED =
      "Multipart Form of only file contents expected.";
  public static final String ERROR_MSG_MULTIPART_CONTENT_FILENAME_EXPECTED =
      "Multipart Form file content must specify instance-relative filename.";
  public static final String ERROR_MSG_MULTIPART_CONTENT_PARSING_FAILED =
      "Multipart Form parsing failed.";
  public static final String ERROR_MSG_MANIFEST_IS_EMPTY_OR_MISSING =
      "Supplied manifest is missing or specifies no files (empty).";
  public static final String ERROR_MSG_INSUFFICIENT_PATH =
      "Not Enough Path Segments: must be at least 1.";
  public static final String ERROR_MSG_UNRECOGNIZED_APP_ID = "Unrecognized app id: ";
  public static final String ERROR_MSG_PATH_NOT_UNDER_APP_ID = "File path is not under app id: ";
  public static final String MIME_TYPE_IMAGE_JPEG = "image/jpeg";

  /**
   * String to stand in for those things in the app's root directory.
   *
   * NOTE: This cannot be null -- GAE doesn't like that!
   */
  public static final String NO_TABLE_ID = "";

  private static final String ERROR_FILE_VERSION_DIFFERS =
      "File on server does not match file being uploaded. Aborting upload. ";

  /**
   * The name of the folder that contains the files associated with a table in an app.
   *
   * @see #getTableIdFromPathSegments(List)
   */
  private final CallingContext cc;
  private final TablesUserPermissions userPermissions;
  private final UriInfo info;
  private final String appId;
  private final String tableId;
  private final String rowId;
  private final String schemaETag;

  public InstanceFileService(String appId, String tableId, String schemaETag, String rowId,
      UriInfo info, TablesUserPermissions userPermissions, CallingContext cc)
      throws ODKEntityNotFoundException, ODKDatastoreException {
    this.cc = cc;
    this.appId = appId;
    this.tableId = tableId;
    this.rowId = rowId;
    this.schemaETag = schemaETag;
    this.info = info;
    this.userPermissions = userPermissions;
  }

  @GET
  @Path("manifest")
  @Produces({MediaType.APPLICATION_JSON, ApiConstants.MEDIA_TEXT_XML_UTF8,
      ApiConstants.MEDIA_APPLICATION_XML_UTF8})
  public Response getManifest(@Context HttpHeaders httpHeaders,
      @QueryParam(PARAM_AS_ATTACHMENT) String asAttachment)
      throws IOException, ODKTaskLockException, PermissionDeniedException {
    UriBuilder ub = info.getBaseUriBuilder();
    ub.path(OdkTables.class);
    ub.path(OdkTables.class, "getTablesService");
    UriBuilder full = ub.clone().path(TableService.class, "getRealizedTable")
        .path(RealizedTableService.class, "getInstanceFiles")
        .path(InstanceFileService.class, "getManifest");
    URI self = full.build(appId, tableId, schemaETag, rowId);
    String manifestUrl = self.toURL().toExternalForm();

    // retrieve the incoming if-none-match eTag...
    List<String> eTags = httpHeaders.getRequestHeader(HttpHeaders.IF_NONE_MATCH);
    String eTag = (eTags == null || eTags.isEmpty()) ? null : eTags.get(0);
    DbTableInstanceManifestETagEntity eTagEntity = null;
    try {
      try {
        eTagEntity = DbTableInstanceManifestETags.getRowIdEntry(tableId, rowId, cc);
      } catch (ODKEntityNotFoundException e) {
        // ignore...
      }

      if (eTag != null && eTagEntity != null && eTag.equals(eTagEntity.getManifestETag())) {
        return Response.status(Status.NOT_MODIFIED).header(HttpHeaders.ETAG, eTag)
            .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Credentials", "true").build();
      }

      InstanceFileManager fm = new InstanceFileManager(appId, cc);

      // get the manifest entries
      final TreeMap<String, FileContentInfo> contents = new TreeMap<String, FileContentInfo>();

      fm.getInstanceAttachments(tableId, rowId, new FileContentHandler() {

        @Override
        public void processFileContent(FileContentInfo content, FetchBlobHandler fetcher) {
          contents.put(content.partialPath, content);

        }
      }, userPermissions);

      // transform to the class used in the REST api
      ArrayList<OdkTablesFileManifestEntry> manifestEntries =
          new ArrayList<OdkTablesFileManifestEntry>();

      for (Map.Entry<String, FileContentInfo> sfci : contents.entrySet()) {
        // these are in sorted order
        OdkTablesFileManifestEntry entry = new OdkTablesFileManifestEntry();
        entry.filename = sfci.getValue().partialPath;
        entry.contentLength = sfci.getValue().contentLength;
        entry.contentType = sfci.getValue().contentType;
        entry.md5hash = sfci.getValue().contentHash;

        URI getFile = ub.clone().path(TableService.class, "getRealizedTable")
            .path(RealizedTableService.class, "getInstanceFiles")
            .path(InstanceFileService.class, "getFile")
            .build(appId, tableId, schemaETag, rowId, entry.filename);
        String locationUrl = getFile.toURL().toExternalForm();
        entry.downloadUrl = locationUrl;

        manifestEntries.add(entry);
      }
      OdkTablesFileManifest manifest = new OdkTablesFileManifest(manifestEntries);

      String newETag = Integer.toHexString(manifest.hashCode());
      // create a new eTagEntity if there isn't one already...
      if (eTagEntity == null) {
        eTagEntity = DbTableInstanceManifestETags.createNewEntity(tableId, rowId, cc);
        eTagEntity.setManifestETag(newETag);
        eTagEntity.put(cc);
      } else if (!newETag.equals(eTagEntity.getManifestETag())) {
        Log log = LogFactory.getLog(FileManifestService.class);
        log.error("TableInstance (" + tableId + "," + rowId
            + ") Manifest ETag does not match computed value!");
        eTagEntity.setManifestETag(newETag);
        eTagEntity.put(cc);
      }

      // and whatever the eTag is in that entity is the eTag we should return...
      eTag = eTagEntity.getManifestETag();

      ResponseBuilder rBuild = Response.ok(manifest).header(HttpHeaders.ETAG, eTag)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true");
      if (asAttachment != null && !"".equals(asAttachment)) {
        // Set the filename we're downloading to the disk.
        rBuild.header(ServletConsts.CONTENT_DISPOSITION,
            "attachment; " + "filename=\"" + "manifest.json" + "\"");
      }
      return rBuild.build();
    } catch (ODKDatastoreException e) {
      e.printStackTrace();
      return Response.status(Status.INTERNAL_SERVER_ERROR)
          .entity("Unable to retrieve manifest of attachments for: " + manifestUrl)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true").build();
    }
  }

  @GET
  @Path("file/{filePath:.*}")
  public Response getFile(@Context HttpHeaders httpHeaders,
      @PathParam("filePath") List<PathSegment> segments,
      @QueryParam(PARAM_AS_ATTACHMENT) String asAttachment)
      throws IOException, ODKTaskLockException, PermissionDeniedException {
    // The appId and tableId are from the surrounding TableService.
    // The rowId is already pulled out.
    // The segments are just rest/of/path in the full app-centric
    // path of:
    // appid/data/attachments/tableid/instances/instanceId/rest/of/path
    if (rowId == null || rowId.length() == 0) {
      return Response.status(Status.BAD_REQUEST)
          .entity(InstanceFileService.ERROR_MSG_INVALID_ROW_ID)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true").build();
    }
    if (segments.size() < 1) {
      return Response.status(Status.BAD_REQUEST)
          .entity(InstanceFileService.ERROR_MSG_INSUFFICIENT_PATH)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true").build();
    }
    // Now construct the whole path.
    String partialPath = constructPathFromSegments(segments);

    // retrieve the incoming if-none-match eTag...
    List<String> eTags = httpHeaders.getRequestHeader(HttpHeaders.IF_NONE_MATCH);
    String eTag = (eTags == null || eTags.isEmpty()) ? null : eTags.get(0);

    UriBuilder ub = info.getBaseUriBuilder();
    ub.path(OdkTables.class);
    ub.path(OdkTables.class, "getTablesService");

    URI getFile = ub.clone().path(TableService.class, "getRealizedTable")
        .path(RealizedTableService.class, "getInstanceFiles")
        .path(InstanceFileService.class, "getFile")
        .build(appId, tableId, schemaETag, rowId, partialPath);

    String locationUrl = getFile.toURL().toExternalForm();

    InstanceFileManager fm = new InstanceFileManager(appId, cc);

    try {
      FileContentInfo fi = fm.getFile(tableId, rowId, partialPath, userPermissions);
      if (fi != null) {
        // And now prepare everything to be returned to the caller.
        if (fi.fileBlob != null && fi.contentType != null && fi.contentLength != null
            && fi.contentLength != 0L) {

          // test if we should return a NOT_MODIFIED response...
          if (eTag != null && eTag.equals(fi.contentHash)) {
            return Response.status(Status.NOT_MODIFIED).header(HttpHeaders.ETAG, eTag)
                .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER,
                    ApiConstants.OPEN_DATA_KIT_VERSION)
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Credentials", "true").build();
          }

          ResponseBuilder responseBuilder =
              Response.ok(fi.fileBlob, fi.contentType).header(HttpHeaders.ETAG, fi.contentHash)
                  .header(HttpHeaders.CONTENT_LENGTH, fi.contentLength)
                  .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER,
                      ApiConstants.OPEN_DATA_KIT_VERSION)
                  .header("Access-Control-Allow-Origin", "*")
                  .header("Access-Control-Allow-Credentials", "true");
          if (asAttachment != null && !"".equals(asAttachment)) {
            // Set the filename we're downloading to the disk.
            responseBuilder.header(ServletConsts.CONTENT_DISPOSITION,
                "attachment; " + "filename=\"" + partialPath + "\"");
          }
          return responseBuilder.build();
        } else {
          return Response.status(Status.NOT_FOUND)
              .entity("File content not yet available for: " + locationUrl)
              .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
              .header("Access-Control-Allow-Origin", "*")
              .header("Access-Control-Allow-Credentials", "true").build();
        }
      }
      return Response.status(Status.NOT_FOUND).entity("No file found for: " + locationUrl)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true").build();
    } catch (ODKDatastoreException e) {
      e.printStackTrace();
      return Response.status(Status.INTERNAL_SERVER_ERROR)
          .entity("Unable to retrieve attachment and access attributes for: " + locationUrl)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true").build();
    }
  }

  /**
   * The JSON is a OdkTablesFileManifest containing the list of files to be returned. The files are
   * returned in a multipart form data response.
   * 
   * @param httpHeaders
   * @param manifest
   * @return
   * @throws IOException
   * @throws ODKTaskLockException
   * @throws PermissionDeniedException
   */
//  @POST
//  @Path("download")
//  @Consumes({MediaType.APPLICATION_JSON, ApiConstants.MEDIA_TEXT_XML_UTF8,
//      ApiConstants.MEDIA_APPLICATION_XML_UTF8})
//  @Produces({MediaType.MULTIPART_FORM_DATA})
//  public Response getFiles(@Context HttpHeaders httpHeaders, OdkTablesFileManifest manifest)
//      throws IOException, ODKTaskLockException, PermissionDeniedException {
//    // The appId and tableId are from the surrounding TableService.
//    // The rowId is already pulled out.
//    // The segments are in the manifest as filenames.
//    // On the device, these filenames are just rest/of/path in the full
//    // app-centric
//    // path of:
//    // appid/data/attachments/tableid/instances/instanceId/rest/of/path
//    if (rowId == null || rowId.length() == 0) {
//      return Response.status(Status.BAD_REQUEST)
//          .entity(InstanceFileService.ERROR_MSG_INVALID_ROW_ID)
//          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
//          .header("Access-Control-Allow-Origin", "*")
//          .header("Access-Control-Allow-Credentials", "true").build();
//    }
//    if (manifest.getFiles() == null || manifest.getFiles().isEmpty()) {
//      return Response.status(Status.BAD_REQUEST)
//          .entity(InstanceFileService.ERROR_MSG_MANIFEST_IS_EMPTY_OR_MISSING)
//          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
//          .header("Access-Control-Allow-Origin", "*")
//          .header("Access-Control-Allow-Credentials", "true").build();
//    }
//
//    UriBuilder ub = info.getBaseUriBuilder();
//    ub.path(OdkTables.class, "getTablesService");
//
//    URI getFile = ub.clone().path(TableService.class, "getRealizedTable")
//        .path(RealizedTableService.class, "getInstanceFiles")
//        .path(InstanceFileService.class, "getFiles").build(appId, tableId, schemaETag, rowId);
//
//    String locationUrl = getFile.toURL().toExternalForm();
//
//    String boundary = "boundary-" + UUID.randomUUID().toString();
//
//    InstanceFileManager instanceFileManager = new InstanceFileManager(appId, cc);
//
//    try {
//      BufferedOutMultiPart mpEntity = new BufferedOutMultiPart();
//      mpEntity.setBoundary(boundary);
//
//      final OutPart[] outParts = new OutPart[manifest.getFiles().size()];
//
//      instanceFileManager.getInstanceAttachments(tableId, rowId, new FileContentHandler() {
//
//        @Override
//        public void processFileContent(FileContentInfo content, FetchBlobHandler fetcher) {
//          // NOTE: this is processed within a critical section
//
//          // see if the server's file entry is in the requested set of files.
//          //
//          int entryIndex = -1;
//          for (int i = 0; i < manifest.getFiles().size(); ++i) {
//            OdkTablesFileManifestEntry entry = manifest.getFiles().get(i);
//            if (entry.filename.equals(content.partialPath)) {
//              entryIndex = i;
//              break;
//            }
//          }
//
//          if (entryIndex != -1) {
//            // it is in the requested set.
//
//            // verify that there is content
//            if (content.contentType != null && content.contentLength != null
//                && content.contentLength != 0L) {
//
//              // get content
//              byte[] fileBlob;
//              try {
//                fileBlob = fetcher.getBlob();
//              } catch (ODKDatastoreException e) {
//                e.printStackTrace();
//                // silently ignore this -- error in this record
//                fileBlob = null;
//              }
//
//              if (fileBlob != null) {
//                // we got the content -- create an OutPart to hold it
//                OutPart op = new OutPart();
//                op.addHeader("Name", "file-" + Integer.toString(entryIndex));
//                String disposition =
//                    "file; filename=\"" + content.partialPath.replace("\"", "\"\"") + "\"";
//                op.addHeader("Content-Disposition", disposition);
//                op.addHeader("Content-Type", content.contentType);
//                op.setBody(fileBlob);
//                outParts[entryIndex] = op;
//              }
//            }
//          }
//
//        }
//      }, userPermissions);
//
//      // assemble the outParts into the body.
//      // These are returned in the same order as they were called.
//      for (int i = 0; i < outParts.length; ++i) {
//        if (outParts[i] != null) {
//          mpEntity.addPart(outParts[i]);
//        }
//      }
//
//      ResponseBuilder rBuild = Response.status(Status.OK).entity(mpEntity)
//          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
//          .header("Access-Control-Allow-Origin", "*")
//          .header("Access-Control-Allow-Credentials", "true");
//      return rBuild.build();
//    } catch (ODKDatastoreException e) {
//      e.printStackTrace();
//      return Response.status(Status.INTERNAL_SERVER_ERROR)
//          .entity("Unable to retrieve attachment and access attributes for: " + locationUrl)
//          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
//          .header("Access-Control-Allow-Origin", "*")
//          .header("Access-Control-Allow-Credentials", "true").build();
//    }
//    return null;
//  }

  /**
   * Takes a multipart form containing the files to be uploaded. The Content-Disposition for each
   * file should specify the instance-relative filepath (using forward slashes). If not specified,
   * an error is reported.
   * 
   * @param req
   * @param bodyParts
   * @param fileDispositions
   * @return string describing error on failure, otherwise empty and Status.CREATED.
   * @throws IOException
   * @throws ODKTaskLockException
   * @throws ODKTablesException
   * @throws ODKDatastoreException
   */
  @POST
  @Path("upload")
  @Consumes({MediaType.MULTIPART_FORM_DATA})
  @Produces({MediaType.APPLICATION_JSON, ApiConstants.MEDIA_TEXT_XML_UTF8,
      ApiConstants.MEDIA_APPLICATION_XML_UTF8})
  public Response postFiles(MultiPart multiPart)
      throws IOException, ODKTaskLockException, ODKTablesException, ODKDatastoreException {

    InstanceFileManager instanceFileManager = new InstanceFileManager(appId, cc);

    instanceFileManager.postFiles(tableId, rowId, multiPart, userPermissions);

    UriBuilder uriBuilder = info.getBaseUriBuilder();
    uriBuilder.path(OdkTables.class, "getTablesService");

    URI getManifest = uriBuilder.clone().path(TableService.class, "getRealizedTable")
        .path(RealizedTableService.class, "getInstanceFiles")
        .path(InstanceFileService.class, "getManifest").build(appId, tableId, schemaETag, rowId);

    String locationUrl = getManifest.toURL().toExternalForm();
    return Response.status(Status.CREATED).header("Location", locationUrl)
        .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
        .header("Access-Control-Allow-Origin", "*")
        .header("Access-Control-Allow-Credentials", "true").build();
  }

  @POST
  @Path("file/{filePath:.*}")
  // @Consumes({MediaType.MEDIA_TYPE_WILDCARD})
  public Response putFile(@Context HttpServletRequest req,
      @PathParam("filePath") List<PathSegment> segments, byte[] content)
      throws IOException, ODKTaskLockException, PermissionDeniedException, ODKDatastoreException {
    if (segments.size() < 1) {
      return Response.status(Status.BAD_REQUEST)
          .entity(InstanceFileService.ERROR_MSG_INSUFFICIENT_PATH)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true").build();
    }
    // The appId and tableId are from the surrounding TableService.
    // The rowId is already pulled out.
    // The segments are just rest/of/path in the full app-centric
    // path of:
    // appid/data/attachments/tableid/instances/instanceId/rest/of/path
    String partialPath = constructPathFromSegments(segments);
    String contentType = req.getContentType();
    String md5Hash = PersistenceUtils.newMD5HashUri(content);

    InstanceFileManager fileInstanceManager = new InstanceFileManager(appId, cc);

    FileContentInfo fileContentInfo =
        new FileContentInfo(partialPath, contentType, (long) content.length, md5Hash, content);
    InstanceFileChangeDetail outcome = fileInstanceManager.putFile(tableId, rowId, fileContentInfo, userPermissions);

    UriBuilder uriBuilder = info.getBaseUriBuilder();
    uriBuilder.path(OdkTables.class);
    uriBuilder.path(OdkTables.class, "getTablesService");

    URI getFile = uriBuilder.clone().path(TableService.class, "getRealizedTable")
        .path(RealizedTableService.class, "getInstanceFiles")
        .path(InstanceFileService.class, "getFile")
        .build(appId, tableId, schemaETag, rowId, partialPath);

    String locationUrl = getFile.toURL().toExternalForm();

    if (outcome == InstanceFileChangeDetail.FILE_PRESENT) {

      return Response.status(Status.CREATED).header("Location", locationUrl)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true").build();

    } else {

      return Response.status(Status.BAD_REQUEST)
          .entity(ERROR_FILE_VERSION_DIFFERS + "\n" + partialPath)
          .header(ApiConstants.OPEN_DATA_KIT_VERSION_HEADER, ApiConstants.OPEN_DATA_KIT_VERSION)
          .header("Access-Control-Allow-Origin", "*")
          .header("Access-Control-Allow-Credentials", "true").build();

    }
  }

  /**
   * Construct the path for the file. This is the entire path excluding the app id.
   *
   * @param segments
   * @return
   */
  private String constructPathFromSegments(List<PathSegment> segments) {
    // Now construct up the path from the segments.
    // We are NOT going to include the app id. Therefore if you upload a file
    // with a path of appid/myDir/myFile.html, the path will be stored as
    // myDir/myFile.html. This is so that when you get the filename on the
    // manifest, it won't matter what is the root directory of your app on your
    // device. Otherwise you might have to strip the first path segment or do
    // something similar.
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (PathSegment segment : segments) {
      sb.append(segment.getPath());
      if (i < segments.size() - 1) {
        sb.append(BasicConsts.FORWARDSLASH);
      }
      i++;
    }
    String wholePath = sb.toString();
    return wholePath;
  }

}