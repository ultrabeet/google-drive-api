package com.directski.service.controllers.google.drive;

import com.directski.data.model.ProductProperties;
import com.directski.service.controllers.GenericController;
import com.directski.service.exceptions.DirectskiException;
import com.directski.service.interfaces.IAsyncEmail;
import com.directski.util.template.FMTemplate;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Permission;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;

@Service
public class GoogleDriveController extends GenericController implements IGoogleDrive{
    private static Logger logger = LoggerFactory.getLogger(GoogleDriveController.class);

    private static final String APPLICATION_NAME = "Google Drive API";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    private enum MimeType{

        FOLDER("application/vnd.google-apps.folder"),
        WORD_DOCUMENT("application/vnd.google-apps.document"),
        SHEETS_DOCUMENT("application/vnd.google-apps.spreadsheet"),
        UNKNOWN("application/vnd.google-apps.unknown");

        private String mimeType;

        MimeType(String mimeType) {
            this.mimeType=mimeType;
        }

        @Override
        public String toString() {
            return this.mimeType;
        }
    }
    @Autowired
    private IAsyncEmail emailController;

    public Drive getDriveService(String product)throws GeneralSecurityException, IOException {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        ProductProperties property=productPropertiesController.getProperty(product, "service.google.drive.secret.JSON");
        if (property == null || StringUtils.isEmpty(property.getPropertyValue())){
            throw new DirectskiException("Could not get Google Drive Service: Could not find property 'service.google.drive.secret.JSON' for product " + product);
        }
        String credentialsJson=property.getPropertyValue();
        GoogleCredential credentials = GoogleCredential.fromStream(IOUtils.toInputStream(credentialsJson, StandardCharsets.UTF_8.name())).createScoped(Collections.singletonList(DriveScopes.DRIVE));
        return new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credentials)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /**
     * @inheritDoc
     */
    @Override
    public void uploadFile(java.io.File file, String email, String product) throws Exception {
        if(file==null) throw new IOException("File does not exist");
        if(!file.getName().contains(".")) throw new IOException("The File does not appear to have a file extension. Please contact the development team.");
        boolean sent=false;
        int attempt=3;
        Exception lastException=null;
        while (!sent&&attempt>0){
            try {
                Drive driveService=getDriveService(product);
                ProductProperties cleanupAfterDaysProperty = productPropertiesController.getProperty(product, "service.google.drive.keep.days");
                Integer cleanupAfterDays=cleanupAfterDaysProperty!=null&&!StringUtils.isEmpty(cleanupAfterDaysProperty.getPropertyValue()) ? Integer.valueOf(cleanupAfterDaysProperty.getPropertyValue()) : null;
                cleanupOldFiles(driveService, cleanupAfterDays);
                ProductProperties reportsFolderNameProperty = productPropertiesController.getProperty(product, "service.google.drive.folder.name");
                if (reportsFolderNameProperty == null || StringUtils.isEmpty(reportsFolderNameProperty.getPropertyValue())){
                    throw new DirectskiException("Could not upload files: Could not find property 'service.google.drive.folder.name' for product " + product);
                }
                List<String> parentList = createFolderIfNotExists(driveService, reportsFolderNameProperty.getPropertyValue());
                File fileMetadata = new File();
                fileMetadata.setName(file.getName());
                fileMetadata.setCreatedTime(new DateTime(new Date()));
                String mimeType;
                if (file.getName().contains(".xls")) {
                    mimeType = MimeType.SHEETS_DOCUMENT.toString();
                } else if (file.getName().contains(".doc")) {
                    mimeType = MimeType.WORD_DOCUMENT.toString();
                } else {
                    mimeType = MimeType.UNKNOWN.toString();
                }
                fileMetadata.setMimeType(mimeType);
                fileMetadata.setParents(parentList);
                FileContent mediaContent = new FileContent(mimeType, file);
                File uploadedFileMetadata = driveService.files().create(fileMetadata, mediaContent)
                        .setFields("*")
                        .execute();
                logger.info("File ID: " + uploadedFileMetadata.getId());
                Permission userPermission = new Permission()
                        .setType("user")
                        .setRole("writer")
                        .setEmailAddress(email);
                driveService.permissions().create(uploadedFileMetadata.getId(), userPermission).setSendNotificationEmail(false)
                        .setFields("*")
                        .execute();
                sendShareLink(uploadedFileMetadata, email, product, cleanupAfterDays);
                sent = true;
            }catch (Exception e){
                lastException=e;
            }
            attempt--;
        }
        if(!sent){
            throw lastException;
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public List<String> createFolderIfNotExists(Drive driveService, String folderName) throws IOException {
        Drive.Files.List request = driveService.files().list();
        List<File> result = new ArrayList<File>();
        do {
            try {
                FileList files = request.setQ("name=\""+folderName+"\"").execute();
                result.addAll(files.getFiles());
                request.setPageToken(files.getNextPageToken());
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                request.setPageToken(null);
            }
        } while (request.getPageToken() != null &&
                request.getPageToken().length() > 0);
        Optional<File> existingFolder=result.stream().filter(f-> folderName.equals(f.getName()) && MimeType.FOLDER.toString().equals(f.getMimeType())).findFirst();
        if(existingFolder==null || !existingFolder.isPresent()){
            File folderMetadata = new File();
            folderMetadata.setName(folderName);
            folderMetadata.setMimeType(MimeType.FOLDER.toString());
            File folder = driveService.files().create(folderMetadata)
                    .setFields("id")
                    .execute();
            logger.info("Folder ID: " + folder.getId());
            Permission userPermission = new Permission()
                    .setType("user")
                    .setRole("writer")
                    .setEmailAddress("gytis@topflight.ie");
            driveService.permissions().create(folder.getId(), userPermission)
                    .setFields("id")
                    .execute();
            logger.info("Folder ID: " + folder.getId());
            return Collections.singletonList(folder.getId());
        }else{
            logger.debug("Folder ID: " + existingFolder.get().getId());
            return Collections.singletonList(existingFolder.get().getId());
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public void cleanupOldFiles(Drive driveService, Integer duration) throws IOException {
        if(duration==null || duration<1){
            logger.debug("Duration less than 0, ignoring cleanup script");
            return;
        }
        if(driveService==null){
            throw new IOException("Drive service cannot be null");
        }
        Calendar c=Calendar.getInstance();
        c.setTime(new Date());
        c.add(Calendar.DATE, duration*-1);
        long calendarAsLong=c.getTime().getTime();
        Drive.Files.List request = driveService.files().list().setFields("files(id, name, mimeType, createdTime, modifiedTime,ownedByMe)");
        List<File> result = new ArrayList<File>();
        do {
            try {
                FileList files = request.execute();
                result.addAll(files.getFiles());
                request.setPageToken(files.getNextPageToken());
            } catch (IOException e) {
                System.out.println("An error occurred: " + e);
                request.setPageToken(null);
            }
        } while (request.getPageToken() != null &&
                request.getPageToken().length() > 0);
        result.stream().forEach(f-> {
            try {
                //long mostRecentModification=f.getModifiedTime()!=null ? f.getModifiedTime().getValue() : f.getCreatedTime().getValue();
                long mostRecentModification = f.getCreatedTime().getValue();
                if (!MimeType.FOLDER.toString().equals(f.getMimeType()) && calendarAsLong > mostRecentModification && BooleanUtils.isTrue(f.getOwnedByMe())) {
                    logger.info("Deleting file ID: " + f.getId());
                    driveService.files().delete(f.getId()).execute();
                }
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        });
    }

    /**
     * Sends an email to {@code email} with a link to {@code file}
     * @param file file to be shared
     * @param email email of the recipient
     * @param deleteAfterDays show in message for how long the file will be kept
     * @param product product to be used while getting account sender
     * @throws Exception
     */
    private void sendShareLink(File file, String email, String product, Integer deleteAfterDays) throws Exception {
        boolean sent=false;
        int attempt=3;
        Exception lastException=null;
        while (!sent&&attempt>0) {
            try {
                emailController.sendMessage(new MimeMessagePreparator() {
                    @Override
                    public void prepare(MimeMessage mimeMessage) throws Exception {
                        mimeMessage.setRecipient(Message.RecipientType.TO, new InternetAddress(email));
                        mimeMessage.setSubject(file.getName() + " - Inviation to edit");
                        Map<String, Object> root = new HashMap<>();
                        root.put("fileName", file.getName());
                        root.put("fileLink", file.getWebViewLink());
                        root.put("fileIconLink", file.getIconLink());
                        if(deleteAfterDays!=null && deleteAfterDays>0) {
                            root.put("deleteAfter", deleteAfterDays);
                        }
                        ProductProperties msg = productPropertiesController.getProperty(product, "service.google.drive.email.template");
                        if (msg == null || StringUtils.isEmpty(msg.getPropertyValue())) {
                            throw new DirectskiException("Could not Send file share email: Could not find property 'service.google.drive.email.template' for product " + product);
                        }
                        mimeMessage.setContent(FMTemplate.make(root, msg.getPropertyValue()), "text/html");
                        mimeMessage.setHeader("Content-Type", "text/html");
                    }
                }, product);
                sent=true;
            } catch (Exception e) {
                lastException = e;
            }
            attempt--;
        }
        if(!sent){
            throw lastException;
        }
    }
}