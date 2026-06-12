package com.agentloop.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the full content of a text file under a configured base directory.
 * Rejects path traversal (../) and symlink escapes.
 */
@Component
@Slf4j
public class FileReadTool {

    private final Path baseDir;
    private final long maxSize;

    public FileReadTool(
            @Value("${agent.file-read.base-dir:${user.dir}}") String baseDir,
            @Value("${agent.file-read.max-size:1048576}") long maxSize
    ) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        this.maxSize = maxSize;
        log.info("FileReadTool base dir: {}, max size: {} bytes", this.baseDir, this.maxSize);
    }

    @Tool(name = "read_file",
          description = "Read a text file under the configured base directory. "
                  + "Paths must be relative to the base directory, or absolute paths within it. "
                  + "Path traversal (../) and symlinks escaping the base directory are rejected.")
    public String apply(
            @ToolParam(description = "file path relative to the base directory, or absolute path within it") String path
    ) {
        if (path == null || path.isBlank()) {
            return "[error] empty path";
        }
        try {
            Path candidate = Path.of(path).isAbsolute()
                    ? Path.of(path).toAbsolutePath().normalize()
                    : baseDir.resolve(path).toAbsolutePath().normalize();

            if (!candidate.startsWith(baseDir)) {
                log.warn("FileReadTool rejected path traversal: input='{}', resolved='{}'", path, candidate);
                return "[error] path escapes base directory";
            }
            if (!Files.exists(candidate)) {
                return "[error] file not found: " + path;
            }
            if (!Files.isRegularFile(candidate)) {
                return "[error] not a regular file: " + path;
            }

            // Resolve symlinks and re-check the boundary
            Path real = candidate.toRealPath();
            if (!real.startsWith(baseDir)) {
                log.warn("FileReadTool rejected symlink escape: input='{}', real='{}'", path, real);
                return "[error] path escapes base directory";
            }

            if (Files.size(real) > maxSize) {
                return "[error] file too large (> " + maxSize + " bytes)";
            }
            log.info("FileReadTool invoked: {}", real);
            return Files.readString(real, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[error] " + e.getMessage();
        }
    }
}
