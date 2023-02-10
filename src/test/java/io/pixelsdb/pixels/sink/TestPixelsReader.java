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
import io.pixelsdb.pixels.core.PixelsReader;
import io.pixelsdb.pixels.core.PixelsReaderImpl;
import io.pixelsdb.pixels.core.TypeDescription;
import io.pixelsdb.pixels.core.reader.PixelsReaderOption;
import io.pixelsdb.pixels.core.reader.PixelsRecordReader;
import io.pixelsdb.pixels.core.vector.VectorizedRowBatch;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.RecordReader;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * @author hank
 */
public class TestPixelsReader
{
    String filePath = "hdfs://dbiir10:9000/pixels/pixels/test_105/v_1_order/20190111212837_0.pxl";

    @Test
    public void testPixelsReader()
    {
        String currentPath = filePath;
        try {
            Storage storage = StorageFactory.Instance().getStorage("hdfs");
            PixelsReader reader = PixelsReaderImpl.newBuilder()
                    .setStorage(storage)
                    .setPath(currentPath)
                    .build();

            TypeDescription schema = reader.getFileSchema();
            List<String> fieldNames = schema.getFieldNames();
            String[] cols = new String[fieldNames.size()];
            for (int i = 0; i < fieldNames.size(); i++) {
                cols[i] = fieldNames.get(i);
            }

            PixelsReaderOption option = new PixelsReaderOption();
            option.skipCorruptRecords(true);
            option.tolerantSchemaEvolution(true);
            option.includeCols(cols);
            PixelsRecordReader recordReader = reader.read(option);
            System.out.println(recordReader.getCompletedRows());
            System.out.println(reader.getRowGroupInfo(0).getNumberOfRows());
            int batchSize = 10000;
            VectorizedRowBatch rowBatch;
            int len = 0;
            int num = 0;
            int row = 0;
            while (true) {
                rowBatch = recordReader.readBatch(batchSize);
                row++;
                String result = rowBatch.toString();
                len += result.length();
                System.out.println("loop:" + row + "," + rowBatch.size);
                if (rowBatch.endOfFile) {
                    num += rowBatch.size;
                    break;
                }
                num += rowBatch.size;
            }
            System.out.println(row + "," + num);
            reader.close();
        } catch (IOException e) {
            System.out.println("Err path: " + currentPath.toString());
            e.printStackTrace();
        }
    }

    String orcPath = "hdfs://dbiir10:9000/pixels/pixels/test_105/v_0_order_orc/20181226133514_0.orc";

    @Test
    public void testOrcReader() {
        Configuration conf = new Configuration();
        Reader reader = null;
        try {
            reader = OrcFile.createReader(new Path(orcPath),
                    OrcFile.readerOptions(conf));
            System.out.println("Row: " + reader.getNumberOfRows());
            RecordReader rows = null;
            rows = reader.rows();
            org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch batch = reader.getSchema().createRowBatch();
            long num = 0;
            int row = 0;
            while (rows.nextBatch(batch)) {
                System.out.println("loop:" + row++ + "," + batch.size);
                if (row == 147)
                    System.out.println(batch.toString());
                num += batch.size;
            }
            System.out.println(row + "," + num);
            rows.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
