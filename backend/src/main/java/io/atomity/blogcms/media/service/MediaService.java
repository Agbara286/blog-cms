package io.atomity.blogcms.media.service;

import io.atomity.blogcms.media.dto.MediaResponse;
import io.atomity.blogcms.media.entity.Media;
import io.atomity.blogcms.media.repository.MediaRepository;
import io.atomity.blogcms.media.storage.StorageService;
import io.atomity.blogcms.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /** Mirrors spring.servlet.multipart.max-file-size; checked here too so the message is precise. */
    static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_FILENAME_LENGTH = 255;

    private final MediaRepository mediaRepository;
    private final StorageService storage;
    private final TransactionTemplate transactionTemplate;

    public MediaService(
            MediaRepository mediaRepository,
            StorageService storage,
            PlatformTransactionManager transactionManager) {
        this.mediaRepository = mediaRepository;
        this.storage = storage;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<MediaResponse> list() {
        List<Media> media = mediaRepository.findAllByOrderByCreatedAtDesc();
        if (media.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> usage = mediaRepository.countUsages(media.stream().map(Media::getId).toList()).stream()
                .collect(Collectors.toMap(MediaRepository.UsageCount::getMediaId, MediaRepository.UsageCount::getUsageCount));
        return media.stream().map(m -> MediaResponse.of(m, usage.getOrDefault(m.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public Media get(UUID id) {
        return mediaRepository.findById(id).orElseThrow(() -> ApiException.notFound("Image"));
    }

    /**
     * Stores the file, then records it. WebDAV runs outside a database
     * transaction so a slow Nextcloud call cannot hold a pool connection.
     * If the database write fails the file is removed again.
     */
    public MediaResponse upload(MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "The file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Images must be 10 MB or smaller");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("READ_FAILED", "Could not read the uploaded file");
        }
        ImageInspector.ImageInfo info = ImageInspector.inspect(bytes);

        UUID id = UUID.randomUUID();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String key = "%d/%02d/%s.%s".formatted(now.getYear(), now.getMonthValue(), id, info.extension());

        storage.store(key, bytes);
        try {
            MediaResponse recorded = transactionTemplate.execute(status -> {
                Media media = mediaRepository.save(Media.create(
                        id,
                        key,
                        cleanFilename(file.getOriginalFilename(), info.extension()),
                        info.contentType(),
                        bytes.length,
                        info.width(),
                        info.height()));
                log.info("Stored image {} ({} bytes, {}x{})", media.getId(), bytes.length, info.width(), info.height());
                return MediaResponse.of(media, 0);
            });
            if (recorded == null) {
                throw new IllegalStateException("Media record was not saved");
            }
            return recorded;
        } catch (RuntimeException ex) {
            try {
                storage.delete(key);
            } catch (RuntimeException cleanupException) {
                log.warn("Failed to delete stored file {} after a database error", key, cleanupException);
                ex.addSuppressed(cleanupException);
            }
            throw ex;
        }
    }

    @Transactional
    public MediaResponse updateAltText(UUID id, String altText) {
        Media media = get(id);
        media.changeAltText(altText);
        return MediaResponse.of(media, mediaRepository.countUsages(id));
    }

    /** Refused while a post or author still uses the image. The file is removed once the delete commits. */
    @Transactional
    public void delete(UUID id) {
        Media media = get(id);
        long usages = mediaRepository.countUsages(id);
        if (usages > 0) {
            throw ApiException.conflict("MEDIA_IN_USE",
                    "This image is used in %d place%s. Remove it there first.".formatted(usages, usages == 1 ? "" : "s"));
        }
        mediaRepository.delete(media);
        String key = media.getStorageKey();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.delete(key);
            }
        });
    }

    /** Keeps only the base name (no client paths), bounded in length. */
    static String cleanFilename(String original, String extension) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty()) {
            name = "image." + extension;
        }
        return name.length() > MAX_FILENAME_LENGTH ? name.substring(name.length() - MAX_FILENAME_LENGTH) : name;
    }
}
