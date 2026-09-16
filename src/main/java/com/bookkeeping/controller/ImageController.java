package com.bookkeeping.controller;

import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.constant.ImageType;
import com.bookkeeping.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.sf.model.response.DataResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;

/**
 * 图片controller
 *
 * @author zhuxiao
 */
@RestController
@RequestMapping("image")
public class ImageController {

    @Value("${bookkeeping.dir}")
    private String bookkeepingDir;

    private EnumMap<ImageType, Path> dirMap;

    /**
     * 上传图片
     *
     * @param file 文件流
     * @return DataResponse<String></String>
     */
    @PostMapping("uploadImage")
    public DataResponse<String> uploadImage(@RequestParam("file") MultipartFile file, @RequestParam("imageType") int imageType) {
        ImageType type = ImageType.of(imageType);
        try {
            String suffix = suffix(file.getOriginalFilename());
            String filename = type.getName() + "." + suffix;
            Path path = dirMap.get(type).resolve(filename);
            file.transferTo(path);
            return DataResponse.of(filename);
        } catch (IOException ex) {
            throw new BusinessException(BookkeepingResp.IMAGE_UPLOAD_FAIL);
        }
    }

    /**
     * 图片下载
     * @param filename 图片名称
     * @param imageType 图片类型
     * @param response  响应
     */
    @GetMapping("download/{filename}/{imageType}")
    public void download(@PathVariable("filename") String filename, @PathVariable("imageType") int imageType, HttpServletResponse response) {
        ImageType type = ImageType.of(imageType);
        Path dir = dirMap.get(type);
        Path path = dir.resolve(filename);
        try {
            response.setHeader("Content-type", "application/octet-stream");
            response.setHeader("Content-disposition", "attachment;filename=" + filename);
            response.getOutputStream().write(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new BusinessException(BookkeepingResp.IMAGE_DOWNLOAD_FAIL);
        }
    }

    private String suffix(String filename) {
        if (StringUtils.isBlank(filename)) {
            return "jpg";
        }
        return !filename.contains(".") ? "jpg" : filename.substring(filename.lastIndexOf(".") + 1);
    }

    @PostConstruct
    public void init() {
        Path imagePath = mkdir(Path.of(bookkeepingDir, "images"));
        this.dirMap = new EnumMap<>(ImageType.class);
        for (ImageType type : ImageType.values()) {
            this.dirMap.put(type, mkdir(imagePath.resolve(type.getDir())));
        }
    }

    private Path mkdir(Path path) {
        if (Files.notExists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new BusinessException(BookkeepingResp.IMAGE_DIR_INIT_FAIL);
            }
        }
        return path;
    }

}
