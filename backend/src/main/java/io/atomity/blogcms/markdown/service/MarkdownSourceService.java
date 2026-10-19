package io.atomity.blogcms.markdown.service;

import io.atomity.blogcms.markdown.dto.MarkdownSourceRef;
import io.atomity.blogcms.markdown.entity.MarkdownSource;
import io.atomity.blogcms.markdown.repository.MarkdownSourceRepository;
import io.atomity.blogcms.media.storage.StorageService;
import io.atomity.blogcms.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class MarkdownSourceService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSourceService.class);

    /** Same limit as the editor's import. */
    static final long MAX_BYTES = 1024 * 1024;
    private static final int MAX_FILENAME_LENGTH = 255;

    private final MarkdownSourceRepository repository;
    private final StorageService storage;

    public MarkdownSourceService(MarkdownSourceRepository repository, StorageService storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public MarkdownSource get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Markdown file"));
    }

    /** Stores the file, then records it. The file is removed again if the database write fails. */
    @Transactional
    public MarkdownSourceRef upload(MultipartFile file) {
        String name = cleanFilename(file.getOriginalFilename());
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".markdown")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE", "Only .md and .markdown files are supported");
        }
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "The file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Markdown files must be 1 MB or smaller");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("READ_FAILED", "Could not read the uploaded file");
        }
        if (!isText(bytes)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE", "The file is not a readable text file");
        }

        UUID id = UUID.randomUUID();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String key = "markdown/%d/%02d/%s.md".formatted(now.getYear(), now.getMonthValue(), id);

        storage.store(key, bytes);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storage.delete(key);
                }
            }
        });

        MarkdownSource source = repository.save(MarkdownSource.create(id, key, name, bytes.length));
        log.info("Stored markdown file {} ({} bytes)", source.getId(), bytes.length);
        return MarkdownSourceRef.of(source);
    }

    /** Valid UTF-8 without NUL characters; anything else is a binary file with a .md name. */
    static boolean isText(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return text.indexOf('\0') < 0;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Keeps only the base name (no client paths), bounded in length. */
    static String cleanFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty()) {
            name = "post.md";
        }
        if (name.length() <= MAX_FILENAME_LENGTH) {
            return name;
        }
        // Keep the end (and the extension); never start on the second half of a two-unit character such as an emoji.
        int start = name.length() - MAX_FILENAME_LENGTH;
        if (Character.isLowSurrogate(name.charAt(start))) {
            start++;
        }
        return name.substring(start);
    }
}
