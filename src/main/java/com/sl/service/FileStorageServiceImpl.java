package com.sl.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * @ClassName FileStorageServiceImpl
 * @Description 上传文件存储，根目录固定为运行目录下的 fileStorage
 * @Version since 1.0
 **/
@Service("fileStorageService")
public class FileStorageServiceImpl implements FileStorageService {

    private final Path path = Paths.get("fileStorage").toAbsolutePath().normalize();

    @Override
    public void init() {
        try {
            // createDirectories：目录已存在时不抛异常，重复启动才不会失败
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize folder for upload!", e);
        }
    }

//    配置上传文件大小
//    spring:
//    	  servlet:
//    	    multipart:
//    	      max-request-size: 50MB
//    	      max-file-size: 50MB
//    application.properties:
//    spring.servlet.multipart.max-request-size=50MB
//    spring.servlet.multipart.max-file-size=50MB

    @Override
    public void save(MultipartFile multipartFile) {
        String originalFilename = multipartFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.trim().isEmpty()) {
            throw new RuntimeException("上传文件名称为空");
        }
        Path target = resolveInsideRoot(originalFilename);
        try (InputStream in = multipartFile.getInputStream()) {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // REPLACE_EXISTING：同名文件覆盖，否则会抛 FileAlreadyExistsException
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Could not store the file. Error:" + e.getMessage(), e);
        }
    }

    @Override
    public Resource load(String filename) {
        Path file = resolveInsideRoot(filename);
        try {
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            }
            throw new RuntimeException("Could not read the file.");
        } catch (MalformedURLException e) {
            throw new RuntimeException("Error:" + e.getMessage(), e);
        }
    }

    @Override
    public Stream<Path> load() {
        try {
            return Files.walk(this.path, 1)
                    .filter(path -> !path.equals(this.path))
                    .map(this.path::relativize);
        } catch (IOException e) {
            throw new RuntimeException("Could not load the files.", e);
        }
    }

    @Override
    public void clear() {
        FileSystemUtils.deleteRecursively(path.toFile());
    }

    /**
     * 把用户传入的文件名解析到存储根目录内部，拦截 ../ 这类路径穿越。
     */
    private Path resolveInsideRoot(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new RuntimeException("文件名称不能为空");
        }
        // 统一只取文件名部分，杜绝 ../ 与绝对路径
        Path target = this.path.resolve(filename).normalize();
        if (!target.startsWith(this.path)) {
            throw new RuntimeException("非法的文件路径：" + filename);
        }
        return target;
    }
}
