/*
 * Copyright 2018-2019 PixelsDB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pixelsdb.pixels.sink;

import io.pixelsdb.pixels.common.utils.DateUtil;
import io.pixelsdb.pixels.common.utils.StringUtil;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hive.ql.exec.vector.*;
import org.apache.orc.CompressionKind;
import org.apache.orc.OrcFile;
import org.apache.orc.TypeDescription;
import org.apache.orc.Writer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class ORCConsumer
        extends Consumer {
    private BlockingQueue<String> queue;
    private Properties prop;
    private Config config;

    public Properties getProp() {
        return prop;
    }

    public ORCConsumer(BlockingQueue<String> queue, Properties prop, Config config) {
        this.queue = queue;
        this.prop = prop;
        this.config = config;
    }

    private enum VECTOR_CLAZZ {
        BytesColumnVector, DoubleColumnVector, LongColumnVector
    }

    // todo fill the runner part of ORCConsumer
    @Override
    public void run() {
        System.out.println("Start PixelsConsumer, " + currentThread().getName() + ", time: " + DateUtil.formatTime(new Date()));
        int count = 0;

        boolean isRunning = true;
        try {
            String loadingDataPath = config.getPixelsPath();
            String schemaStr = config.getSchema();
            int[] orderMapping = config.getOrderMapping();
            int maxRowNum = config.getMaxRowNum();
            String regex = config.getRegex();

            Properties prop = getProp();
            int pixelStride = Integer.parseInt(prop.getProperty("pixel.stride"));
            int rowGroupSize = Integer.parseInt(prop.getProperty("row.group.size"));
            long blockSize = Long.parseLong(prop.getProperty("block.size"));
            short replication = Short.parseShort(prop.getProperty("block.replication"));

            Configuration conf = new Configuration();
            conf.set("fs.hdfs.impl", DistributedFileSystem.class.getName());
            conf.set("fs.file.impl", LocalFileSystem.class.getName());
            FileSystem fs = FileSystem.get(URI.create(loadingDataPath), conf);
            TypeDescription schema = TypeDescription.fromString(schemaStr);
            VectorizedRowBatch rowBatch = schema.createRowBatch();
            ColumnVector[] columnVectors = rowBatch.cols;

            BufferedReader reader;
            String line;

            boolean initPixelsFile = true;
            String loadingFilePath;
            Writer orcWriter = null;
            int rowCounter = 0;

            while (isRunning) {
                String originalFilePath = queue.poll(2, TimeUnit.SECONDS);
                if (originalFilePath != null) {
                    count++;
                    reader = new BufferedReader(new InputStreamReader(fs.open(new Path(originalFilePath))));

                    while ((line = reader.readLine()) != null) {
                        if (initPixelsFile == true) {
                            if (line.length() == 0) {
                                System.out.println(currentThread().getName() + "\tcontent: (" + line + ")");
                                continue;
                            }
                            // we create a new orc file if we can read a next line from the source file.
                            loadingFilePath = loadingDataPath + DateUtil.getCurTime() + ".orc";
                            orcWriter = OrcFile.createWriter(new Path(loadingFilePath),
                                    OrcFile.writerOptions(conf)
                                            .setSchema(schema)
                                            .rowIndexStride(pixelStride)
                                            .stripeSize(rowGroupSize)
                                            .blockSize(blockSize)
                                            .blockPadding(true)
                                            .compress(CompressionKind.NONE)
                                            .fileSystem(fs)
                            );
                        }
                        initPixelsFile = false;

                        line = StringUtil.replaceAll(line, "false", "0");
                        line = StringUtil.replaceAll(line, "False", "0");
                        line = StringUtil.replaceAll(line, "true", "1");
                        line = StringUtil.replaceAll(line, "True", "1");
                        int rowId = rowBatch.size++;
                        rowCounter++;
                        if (regex.equals("\\s")) {
                            regex = " ";
                        }
                        String[] colsInLine = line.split(regex);
                        for (int i = 0; i < columnVectors.length; i++) {
                            int valueIdx = orderMapping[i];
                            if (colsInLine[valueIdx].equalsIgnoreCase("\\N")) {
                                columnVectors[i].isNull[rowId] = true;
                            } else {
                                String value = colsInLine[valueIdx];
                                VECTOR_CLAZZ clazz = VECTOR_CLAZZ.valueOf(columnVectors[i].getClass().getSimpleName());
                                switch (clazz) {
                                    case BytesColumnVector:
                                        BytesColumnVector bytesColumnVector = (BytesColumnVector) columnVectors[i];
                                        bytesColumnVector.setVal(rowId, value.getBytes());
                                        break;
                                    case DoubleColumnVector:
                                        DoubleColumnVector doubleColumnVector = (DoubleColumnVector) columnVectors[i];
                                        doubleColumnVector.vector[rowId] = Double.valueOf(value);
                                        break;
                                    case LongColumnVector:
                                        LongColumnVector longColumnVector = (LongColumnVector) columnVectors[i];
                                        longColumnVector.vector[rowId] = Long.valueOf(value);
                                        break;
                                }
                            }
                        }

                        if (rowBatch.size >= rowBatch.getMaxSize()) {
                            orcWriter.addRowBatch(rowBatch);
                            rowBatch.reset();
                            if (rowCounter >= maxRowNum) {
                                orcWriter.close();
                                rowCounter = 0;
                                initPixelsFile = true;
                            }
                        }
                    }
                    reader.close();
                } else {
                    // no source file can be consumed within 2 seconds,
                    // loading is considered to be finished.
                    isRunning = false;
                }

            }

            if (rowCounter > 0) {
                // left last file to write
                if (rowBatch.size != 0) {
                    orcWriter.addRowBatch(rowBatch);
                    rowBatch.reset();
                }
                orcWriter.close();
            }
        } catch (InterruptedException e) {
            System.out.println("ORCConsumer: " + e.getMessage());
            currentThread().interrupt();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            System.out.println(currentThread().getName() + ":" + count);
            System.out.println("Exit ORCConsumer, " + currentThread().getName() + ", time: " + DateUtil.formatTime(new Date()));
        }
    }
}