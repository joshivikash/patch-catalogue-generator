package com.cisco.nccm.patch.catalogue.generator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

public class PatchCatalogueGeneratorMain {
    private static Logger          logger   = Logger.getLogger(PatchCatalogueGeneratorMain.class);
    private static ZipFile         zipFile1;
    private static ZipFile         zipFile2;
    private static Path            catalogueFilePath;
    private static ExecutorService executorService;
    private static ThreadGroup     threadGroup;
    private static final String    NEW_LINE = "\n";

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out
                    .println("Usage : java -jar nccm-patch-catalogue-generator-<version>.jar <file1.zip> <file2.zip>");
            return;
        }
        init(args);
    }

    private static void init(String[] args) {
        PropertyConfigurator.configure("log4j.properties");
        try {
            catalogueFilePath = Paths.get("catalogue.csv");
            Files.deleteIfExists(catalogueFilePath);
            Files.createFile(catalogueFilePath);
            Files.write(catalogueFilePath, "Status,FileName".getBytes(), StandardOpenOption.WRITE);
            threadGroup = (System.getSecurityManager() != null) ? System.getSecurityManager().getThreadGroup()
                    : Thread.currentThread().getThreadGroup();
            generatePatchCatalogue(args[0], args[1]);
        } catch (Exception e) {
            logger.error("Error during initialization", e);
        }
    }

    private static void generatePatchCatalogue(String file1, String file2) {
        Thread addModifyFileListerThread = new Thread(() -> {
            generateAddModifiedFilesCatalogue();
        });
        addModifyFileListerThread.setName("AddModifyFileListerThread");

        Thread deletedFileListerThread = new Thread(() -> {
            generateDeletedFilesCatalogue();
        });
        deletedFileListerThread.setName("DeletedFileListerThread");

        Thread extractJarsThread = new Thread(() -> {
            extractJars();
        });
        extractJarsThread.setName("ExtractJarsThread");

        try {
            zipFile1 = new ZipFile(file1);
            zipFile2 = new ZipFile(file2);
            extractJarsThread.start();
            extractJarsThread.join();
            addModifyFileListerThread.start();
            addModifyFileListerThread.join();
            deletedFileListerThread.start();
            deletedFileListerThread.join();
            zipFile1.close();
            zipFile2.close();
        } catch (InterruptedException e) {
            logger.error("Interupted while waiting for main thread to complete", e);
        } catch (Exception e) {
            logger.error("Error while creating ZipFile Objects", e);
        }
    }

    private static void extractJars() {
        extractJarFromLatestServerZip(zipFile1, "nccmInstallUtil.jar");
        try {
            extractJarFromLatestServerZip(zipFile1, "nccmweb.war");
            ZipFile zipFile = new ZipFile("nccmweb.war");
            extractJarFromLatestServerZip(zipFile, "pari_audit_api.jar");
            zipFile.close();
            Files.deleteIfExists(Paths.get("nccmweb.war"));
        } catch (Exception e) {
            logger.error("Error extrating pari_audit_api.jar", e);
        }
    }

    private static void extractJarFromLatestServerZip(ZipFile zipFile, String fileName) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry zipEntry = (ZipEntry) entries.nextElement();
            if (zipEntry.getName().endsWith(fileName)) {
                InputStream inputStream = null;
                try {
                    inputStream = zipFile.getInputStream(zipEntry);
                    Files.copy(inputStream, Paths.get(fileName), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    logger.error("Error while extracting " + fileName, e);
                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (Exception e) {
                            logger.error("Extracting " + fileName + ".Error while closing input stream", e);
                        }
                    }
                }
                break;
            }
        }
    }

    private static void generateDeletedFilesCatalogue() {
        try {
            Enumeration<? extends ZipEntry> zipEntries = zipFile2.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry zipEntry2 = zipEntries.nextElement();
                ZipEntry zipEntry1 = zipFile1.getEntry(zipEntry2.getName());
                if (zipEntry1 == null) {
                    synchronized (catalogueFilePath) {
                        Files.write(catalogueFilePath, (NEW_LINE + "D," + zipEntry2.getName()).getBytes(),
                                StandardOpenOption.APPEND);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("error while populating added and modified files in catalogue file", e);
        }
    }

    private static void generateAddModifiedFilesCatalogue() {
        try {
            Enumeration<? extends ZipEntry> zipEntries = zipFile1.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry zipEntry1 = zipEntries.nextElement();
                ZipEntry zipEntry2 = zipFile2.getEntry(zipEntry1.getName());
                // If a file is newly added
                if (zipEntry2 == null) {
                    synchronized (catalogueFilePath) {
                        Files.write(catalogueFilePath, (NEW_LINE + "A," + zipEntry1.getName()).getBytes(),
                                StandardOpenOption.APPEND);
                    }
                } else {
                    executorService = (executorService == null) ? Executors.newFixedThreadPool(10, (r) -> {
                        return new Thread(threadGroup, r, zipEntry2.getName() + "-Thread");
                    }) : executorService;

                    executorService.submit(() -> {
                        populateModifiedFilesList(zipEntry1, zipEntry2);
                    });
                }
            }
            executorService.shutdown();
            executorService.awaitTermination(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("Error while populating catalogue file with modified and added files", e);
        }
    }

    private static void populateModifiedFilesList(ZipEntry zipEntry1, ZipEntry zipEntry2) {
        InputStream inputStream1 = null, inputStream2 = null;
        try {
            inputStream1 = zipFile1.getInputStream(zipEntry1);
            inputStream2 = zipFile2.getInputStream(zipEntry2);
            String checkSum1 = calcCheckSum(inputStream1, zipEntry1.getName());
            String checkSum2 = calcCheckSum(inputStream2, zipEntry2.getName());
            if (checkSum1 != null && !checkSum1.equals(checkSum2)) {
                synchronized (catalogueFilePath) {
                    Files.write(catalogueFilePath, (NEW_LINE + "M," + zipEntry1.getName()).getBytes(),
                            StandardOpenOption.APPEND);
                }
            }
        } catch (Exception e) {
            logger.error("Error while comparing files", e);
        } finally {
            if (inputStream1 != null) {
                try {
                    inputStream1.close();
                } catch (Exception e) {
                    logger.error("Comparing Files. Error while closing input stream", e);
                }
            }
            if (inputStream2 != null) {
                try {
                    inputStream2.close();
                } catch (Exception e) {
                    logger.error("Comparing Files. Error while closing input stream", e);
                }
            }
        }
    }

    private static String calcCheckSum(InputStream inputStream, String file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] byteArr = new byte[1024];
            int readBytes = 0;
            while ((readBytes = inputStream.read(byteArr)) != -1) {
                md.update(byteArr, 0, readBytes);
            }
            byte[] mdBytes = md.digest();

            StringBuilder hexString = new StringBuilder();
            for (int j = 0; j < mdBytes.length; j++) {
                hexString.append(Integer.toHexString(0xFF & mdBytes[j]));
            }
            return hexString.toString();
        } catch (Exception e) {
            logger.error("Error generating CheckSum SHA512", e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception e) {
                    logger.error("Error closing input stream : ", e);
                }
            }
        }
        return null;
    }
}
