package com.directski.service.controllers.google.drive;

import com.google.api.services.drive.Drive;

import java.io.IOException;
import java.util.List;

public interface IGoogleDrive {

    /**
     * Calls {@code cleanupOldFiles} and {@code createFolderIfNotExists}, uploads a {@code file} to google drive and sends a sharing link to {@code email}
     * @param file file to be sent to Google Drive
     * @param product product code used to pick the 'share' email sender
     * @param email email of the person to share the file with
     * @throws IOException If cannot connect to google drive API
     */
    void uploadFile(java.io.File file, String email, String product) throws Exception;

    /**
     * Checks if a folder exists and creates if it doesn't
     * @param driveService authenticated drive service
     * @return {@code List<String>} containing the google drive folder ID
     * @throws IOException If cannot connect to google drive API
     */
    List<String> createFolderIfNotExists(Drive driveService, String folderName) throws IOException;

    /**
     * Cleans up files not modified for more than {@code duration} days
     * @param driveService authenticated drive service
     * @param duration number of days to keep the file
     * @throws IOException If cannot connect to google drive API
     **/
    void cleanupOldFiles(Drive driveService, Integer duration) throws IOException;
}
