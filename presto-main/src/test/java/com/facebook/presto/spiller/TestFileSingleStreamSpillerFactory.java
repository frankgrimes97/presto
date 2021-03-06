/*
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
package com.facebook.presto.spiller;

import com.facebook.presto.block.BlockEncodingManager;
import com.facebook.presto.memory.AggregatedMemoryContext;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.block.BlockEncodingSerde;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.type.TypeRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Closer;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.airlift.testing.FileUtils;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spiller.FileSingleStreamSpillerFactory.SPILL_FILE_PREFIX;
import static com.facebook.presto.spiller.FileSingleStreamSpillerFactory.SPILL_FILE_SUFFIX;
import static com.google.common.util.concurrent.Futures.getUnchecked;
import static org.testng.Assert.assertEquals;

@Test(singleThreaded = true)
public class TestFileSingleStreamSpillerFactory
{
    private final Closer closer = Closer.create();
    private ListeningExecutorService executor;
    private File spillPath1;
    private File spillPath2;

    @BeforeMethod
    public void setUp()
            throws Exception
    {
        executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
        closer.register(() -> executor.shutdownNow());
        spillPath1 = Files.createTempDir();
        closer.register(() -> FileUtils.deleteRecursively(spillPath1));
        spillPath2 = Files.createTempDir();
        closer.register(() -> FileUtils.deleteRecursively(spillPath2));
    }

    @AfterMethod
    public void tearDown()
            throws Exception
    {
        closer.close();
    }

    @Test
    public void testDistributesSpillOverPaths()
            throws Exception
    {
        List<Type> types = ImmutableList.of(BIGINT);
        BlockEncodingSerde blockEncodingSerde = new BlockEncodingManager(new TypeRegistry(ImmutableSet.copyOf(types)));
        List<Path> spillPaths = ImmutableList.of(spillPath1.toPath(), spillPath2.toPath());
        FileSingleStreamSpillerFactory spillerFactory = new FileSingleStreamSpillerFactory(
                executor,
                blockEncodingSerde,
                new SpillerStats(),
                spillPaths,
                1.0);

        assertEquals(FileUtils.listFiles(spillPath1).size(), 0);
        assertEquals(FileUtils.listFiles(spillPath2).size(), 0);

        Page page = buildPage();
        List<SingleStreamSpiller> spillers = new ArrayList<>();
        for (int i = 0; i < 10; ++i) {
            SingleStreamSpiller singleStreamSpiller = spillerFactory.create(types, bytes -> { }, new AggregatedMemoryContext().newLocalMemoryContext());
            getUnchecked(singleStreamSpiller.spill(page));
            spillers.add(singleStreamSpiller);
        }
        assertEquals(FileUtils.listFiles(spillPath1).size(), 5);
        assertEquals(FileUtils.listFiles(spillPath2).size(), 5);

        spillers.forEach(SingleStreamSpiller::close);
        assertEquals(FileUtils.listFiles(spillPath1).size(), 0);
        assertEquals(FileUtils.listFiles(spillPath2).size(), 0);
    }

    private Page buildPage()
    {
        BlockBuilder col1 = BIGINT.createBlockBuilder(new BlockBuilderStatus(), 1);
        col1.writeLong(42).closeEntry();
        return new Page(col1.build());
    }

    @Test(expectedExceptions = RuntimeException.class, expectedExceptionsMessageRegExp = "No free space available for spill")
    public void throwsIfNoDiskSpace()
    {
        List<Type> types = ImmutableList.of(BIGINT);
        BlockEncodingSerde blockEncodingSerde = new BlockEncodingManager(new TypeRegistry(ImmutableSet.copyOf(types)));
        List<Path> spillPaths = ImmutableList.of(spillPath1.toPath(), spillPath2.toPath());
        FileSingleStreamSpillerFactory spillerFactory = new FileSingleStreamSpillerFactory(
                executor,
                blockEncodingSerde,
                new SpillerStats(),
                spillPaths,
                0.0);

        spillerFactory.create(types, bytes -> { }, new AggregatedMemoryContext().newLocalMemoryContext());
    }

    @Test
    public void testCleanupOldSpillFiles()
            throws Exception
    {
        List<Type> types = ImmutableList.of(BIGINT);
        BlockEncodingSerde blockEncodingSerde = new BlockEncodingManager(new TypeRegistry(ImmutableSet.copyOf(types)));
        List<Path> spillPaths = ImmutableList.of(spillPath1.toPath(), spillPath2.toPath());
        spillPath1.mkdirs();
        spillPath2.mkdirs();

        java.nio.file.Files.createTempFile(spillPath1.toPath(), SPILL_FILE_PREFIX, SPILL_FILE_SUFFIX);
        java.nio.file.Files.createTempFile(spillPath1.toPath(), SPILL_FILE_PREFIX, SPILL_FILE_SUFFIX);
        java.nio.file.Files.createTempFile(spillPath1.toPath(), SPILL_FILE_PREFIX, "blah");
        java.nio.file.Files.createTempFile(spillPath2.toPath(), SPILL_FILE_PREFIX, SPILL_FILE_SUFFIX);
        java.nio.file.Files.createTempFile(spillPath2.toPath(), "blah", SPILL_FILE_SUFFIX);
        java.nio.file.Files.createTempFile(spillPath2.toPath(), "blah", "blah");

        assertEquals(FileUtils.listFiles(spillPath1).size(), 3);
        assertEquals(FileUtils.listFiles(spillPath2).size(), 3);

        FileSingleStreamSpillerFactory spillerFactory = new FileSingleStreamSpillerFactory(
                executor,
                blockEncodingSerde,
                new SpillerStats(),
                spillPaths,
                1.0);
        spillerFactory.cleanupOldSpillFiles();

        assertEquals(FileUtils.listFiles(spillPath1).size(), 1);
        assertEquals(FileUtils.listFiles(spillPath2).size(), 2);
    }
}
