/*******************************************************************************
 * Copyright (C) 2015 Josef Cacek
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package com.github.kwart.jd.input;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.github.kwart.jd.output.DirOutput;
import org.jd.core.v1.api.loader.LoaderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kwart.jd.IOUtils;
import com.github.kwart.jd.JavaDecompiler;
import com.github.kwart.jd.loader.CachedLoader;
import com.github.kwart.jd.options.DecompilerOptions;
import com.github.kwart.jd.output.JDOutput;

/**
 * Input plugin for ZIP files (e.g. jar, war, ...)
 *
 * @author Josef Cacek
 */
public class ZipFileInput extends AbstractFileJDInput {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZipFileInput.class);

    /**
     * Constructor.
     */
    public ZipFileInput(String path) {
        this(path, null);
    }

    /**
     * Constructor.
     */
    public ZipFileInput(String filePath, String pattern) throws IllegalArgumentException {
        this(filePath, pattern, null);
    }

    public ZipFileInput(String filePath, String pattern, String nonPattern) throws IllegalArgumentException {
        super(filePath, pattern, nonPattern);
    }

    /*
     * write zip entry .jar to temp file
     */
    private File writeZipEntry(ZipInputStream zis) {
        FileOutputStream fos = null;
        File f = null;
        try {
            f = File.createTempFile("jdZipTemp-", ".jar");
            fos = new FileOutputStream(f);
            IOUtils.copy(zis, fos);
        } catch (Exception ex) {
            LOGGER.error("Exception RAISED!!! ", ex);
        } finally {
            IOUtils.closeQuietly(fos);
        }
        return f;
    }

    /**
     * Parses all entres in the zip and decompiles it writing results to {@link JDOutput} instance.
     *
     * @see com.github.kwart.jd.input.JDInput#decompile(com.github.kwart.jd.JavaDecompiler, com.github.kwart.jd.output.JDOutput)
     */
    @Override
    public void decompile(JavaDecompiler javaDecompiler, JDOutput jdOutput) {
        if (javaDecompiler == null || jdOutput == null) {
            LOGGER.warn("Decompiler or JDOutput are null");
            return;
        }

        DecompilerOptions options = javaDecompiler.getOptions();
        boolean skipResources = options.isSkipResources();

        LOGGER.debug("Initializing decompilation of a zip file {}", file);

        jdOutput.init(options, file.getPath());
        ZipInputStream zis = null;
        CachedLoader cachedLoader = new CachedLoader();
        try {
            zis = new ZipInputStream(new FileInputStream(file));
            ZipEntry entry = null;

            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    final String entryName = entry.getName();
                    if (skipThePath(entryName)) {
                        continue;
                    }
                    if (IOUtils.isClassFile(entryName)) {
                        LOGGER.debug("Caching {}", entryName);
                        processClass(zis, cachedLoader, entryName);
                    } else if (isProcessInnerJar(options, entryName)) {
                        LOGGER.debug("Caching {}", entryName);
                        processInnerJar(javaDecompiler,
                                zis,
                                getInnerDir(jdOutput, entryName));
                    } else if (!skipResources) {
                        LOGGER.debug("Processing resource file {}", entryName);
                        jdOutput.processResource(entryName, zis);
                    } else {
                        LOGGER.trace("Skipping resource file {}", entryName);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Exception occured", e);
        } finally {
            IOUtils.closeQuietly(zis);
        }
        Stream<String> classNamesStream = options.isParallelProcessingAllowed()
                ? cachedLoader.getClassNames().parallelStream()
                : cachedLoader.getClassNames().stream();
        classNamesStream.filter(s -> !IOUtils.isInnerClass(s)).map(IOUtils::cutClassSuffix).forEach(name -> {
            try {
                jdOutput.processClass(name, javaDecompiler.decompileClass(cachedLoader, name));
            } catch (Exception e) {
                LOGGER.error("Exception when decompiling class " + name, e);
            }
        });
        jdOutput.commit();
    }


    private boolean isProcessInnerJar(DecompilerOptions options, String entryName) {
        return options.isDecompileInnerJar()
                && (IOUtils.isJarFile(entryName)
                || IOUtils.isZipFile(entryName)
                || IOUtils.isWarFile(entryName)
                || IOUtils.isEarFile(entryName));
    }

    private void processClass(ZipInputStream zis, CachedLoader cachedLoader, String entryName) {
        try {
            cachedLoader.addClass(entryName, zis);
        } catch (LoaderException e) {
            LOGGER.error("LoaderException occured", e);
        }
    }

    private String getInnerDir(JDOutput jdOutput, String entryName) {
        return ((jdOutput.getTargetDir() == null)
                ? ""
                : jdOutput.getTargetDir().getPath() + File.separator)
                + entryName;
    }

    private void processInnerJar(JavaDecompiler javaDecompiler, ZipInputStream zis, String entryName) {
        File f = null;
        try {
            f = writeZipEntry(zis);
            JDInput jdIn = new ZipFileInput(f.getPath(), null);
            jdIn.decompile(javaDecompiler,
                    new DirOutput(
                            new File(entryName + ".src")));
        } catch (Exception ex) {
            LOGGER.error("Processing inner jar Exception", ex);
        } finally {
            if (f != null) {
                f.delete();
            }
        }
    }
}
