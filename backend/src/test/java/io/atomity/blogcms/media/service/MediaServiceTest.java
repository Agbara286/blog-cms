package io.atomity.blogcms.media.service;

import io.atomity.blogcms.media.TestImages;
import io.atomity.blogcms.media.repository.MediaRepository;
import io.atomity.blogcms.media.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaServiceTest {

    @Test
    void uploadKeepsDatabaseExceptionWhenStorageCleanupFails() {
        MediaRepository mediaRepository = mock(MediaRepository.class);
        StorageService storage = mock(StorageService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);

        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        RuntimeException databaseFailure = new RuntimeException("database write failed");
        when(mediaRepository.save(any())).thenThrow(databaseFailure);
        RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
        doThrow(cleanupFailure).when(storage).delete(any());

        MediaService mediaService = new MediaService(mediaRepository, storage, transactionManager);
        var file = new MockMultipartFile(
                "file", "photo.png", "image/png", TestImages.encode("png", 8, 8));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> mediaService.upload(file));

        assertSame(databaseFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
        verify(storage).delete(any());
    }

    @Test
    void uploadCleansUpStoredFileWhenDatabaseWriteThrowsError() {
        MediaRepository mediaRepository = mock(MediaRepository.class);
        StorageService storage = mock(StorageService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);

        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        AssertionError databaseFailure = new AssertionError("database write failed");
        when(mediaRepository.save(any())).thenThrow(databaseFailure);

        MediaService mediaService = new MediaService(mediaRepository, storage, transactionManager);
        var file = new MockMultipartFile(
                "file", "photo.png", "image/png", TestImages.encode("png", 8, 8));

        AssertionError thrown = assertThrows(AssertionError.class, () -> mediaService.upload(file));

        assertSame(databaseFailure, thrown);
        verify(storage).store(any(), any());
        verify(storage).delete(any());
    }
}
