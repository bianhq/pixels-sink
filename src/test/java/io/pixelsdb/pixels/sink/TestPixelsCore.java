/*
 * Copyright 2018 PixelsDB.
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

import io.pixelsdb.pixels.common.physical.Storage;
import io.pixelsdb.pixels.common.physical.StorageFactory;
import io.pixelsdb.pixels.core.*;
import io.pixelsdb.pixels.core.exception.PixelsWriterException;
import io.pixelsdb.pixels.core.vector.*;
import org.junit.Test;

import java.io.IOException;
import java.sql.Timestamp;

/**
 * @author: tao
 * @date: Create in 2018-11-07 16:05
 **/
public class TestPixelsCore
{

    String hdfsDir = "/home/tao/data/hadoop-2.7.3/etc/hadoop/"; // dbiir10

    @Test
    public void testReadPixelsFile() throws IOException
    {
        String pixelsFile = "hdfs://dbiir10:9000/pixels/pixels/test_105/20181117213047_3239.pxl";
        Storage storage = StorageFactory.Instance().getStorage("hdfs");

        PixelsReader pixelsReader = null;
        try {
            pixelsReader = PixelsReaderImpl.newBuilder()
                    .setStorage(storage)
                    .setPath(pixelsFile)
                    .build();
            System.out.println(pixelsReader.getRowGroupNum());
            System.out.println(pixelsReader.getRowGroupInfo(0).toString());
            System.out.println(pixelsReader.getRowGroupInfo(1).toString());
            if (pixelsReader.getFooter().getRowGroupStatsList().size() != 1) {
                System.out.println("Path: " + pixelsFile + ", RGNum: " + pixelsReader.getRowGroupNum());
            }

            pixelsReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testWritePixelsFile() throws IOException
    {
        String pixelsFile = "hdfs://dbiir10:9000//pixels/pixels/test_105/v_0_order/.pxl";
        Storage storage = StorageFactory.Instance().getStorage("hdfs");

        // schema: struct<a:int,b:float,c:double,d:timestamp,e:boolean,z:string>
        String schemaStr = "struct<a:int,b:float,c:double,d:timestamp,e:boolean,z:string>";

        try {
            TypeDescription schema = TypeDescription.fromString(schemaStr);
            VectorizedRowBatch rowBatch = schema.createRowBatch();
            LongColumnVector a = (LongColumnVector) rowBatch.cols[0];              // int
            DoubleColumnVector b = (DoubleColumnVector) rowBatch.cols[1];          // float
            DoubleColumnVector c = (DoubleColumnVector) rowBatch.cols[2];          // double
            TimestampColumnVector d = (TimestampColumnVector) rowBatch.cols[3];    // timestamp
            LongColumnVector e = (LongColumnVector) rowBatch.cols[4];              // boolean
            BinaryColumnVector z = (BinaryColumnVector) rowBatch.cols[5];            // string

            PixelsWriter pixelsWriter =
                    PixelsWriterImpl.newBuilder()
                            .setSchema(schema)
                            .setPixelStride(10000)
                            .setRowGroupSize(64 * 1024 * 1024)
                            .setStorage(storage)
                            .setPath(pixelsFile)
                            .setBlockSize(256 * 1024 * 1024)
                            .setReplication((short) 3)
                            .setBlockPadding(true)
                            .setEncoding(true)
                            .setCompressionBlockSize(1)
                            .build();

            long curT = System.currentTimeMillis();
            Timestamp timestamp = new Timestamp(curT);
            for (int i = 0; i < 1; i++) {
                int row = rowBatch.size++;
                a.vector[row] = i;
                a.isNull[row] = false;
                b.vector[row] = Float.floatToIntBits(i * 3.1415f);
                b.isNull[row] = false;
                c.vector[row] = Double.doubleToLongBits(i * 3.14159d);
                c.isNull[row] = false;
                d.set(row, timestamp);
                d.isNull[row] = false;
                e.vector[row] = i > 25000 ? 1 : 0;
                e.isNull[row] = false;
                z.setVal(row, String.valueOf(i).getBytes());
                z.isNull[row] = false;
                if (rowBatch.size == rowBatch.getMaxSize()) {
                    pixelsWriter.addRowBatch(rowBatch);
                    rowBatch.reset();
                }
            }

            if (rowBatch.size != 0) {
                pixelsWriter.addRowBatch(rowBatch);
                rowBatch.reset();
            }

            pixelsWriter.close();
        } catch (IOException | PixelsWriterException e) {
            e.printStackTrace();
        }
    }

}
