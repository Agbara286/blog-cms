package io.atomity.blogcms.media.storage;

import io.atomity.blogcms.shared.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Profile("test")
@Service
public class InMemoryStorageService implements StorageService {

    private final ConcurrentMap<String, byte[]> files = new ConcurrentHashMap<>();

    @Override
    public void store(String key, byte[] content) {
        files.put(key, content.clone());
    }

    @Override
    public Resource load(String key) {
        byte[] content = files.get(key);
        if (content == null) {
            throw ApiException.notFound("Media file");
        }
        return new ByteArrayResource(content.clone());
    }

    @Override
    public void delete(String key) {
        files.remove(key);
    }
}
