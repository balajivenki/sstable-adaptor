/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.io.sstable.format;

import com.google.common.collect.Sets;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.RowIndexEntry;
import org.apache.cassandra.db.SerializationHeader;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.FSWriteError;
import org.apache.cassandra.io.sstable.Component;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.SSTable;
import org.apache.cassandra.io.sstable.format.big.BigFormat;
import org.apache.cassandra.io.sstable.metadata.MetadataCollector;
import org.apache.cassandra.io.sstable.metadata.MetadataComponent;
import org.apache.cassandra.io.sstable.metadata.MetadataType;
import org.apache.cassandra.io.sstable.metadata.StatsMetadata;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.concurrent.Transactional;

import java.util.*;

/**
 * This is the API all table writers must implement.
 *
 * TableWriter.create() is the primary way to create a writer for a particular format.
 * The format information is part of the Descriptor.
 */
public abstract class SSTableWriter extends SSTable implements Transactional
{
    protected long repairedAt;
    protected long maxDataAge = -1;
    protected final long keyCount;
    protected final MetadataCollector metadataCollector;
    protected final RowIndexEntry.IndexSerializer rowIndexEntrySerializer;
    protected final SerializationHeader header;
    protected final TransactionalProxy txnProxy = txnProxy();
    protected final Collection<SSTableFlushObserver> observers;

    protected abstract TransactionalProxy txnProxy();

    // due to lack of multiple inheritance, we use an inner class to proxy our Transactional implementation details
    protected abstract class TransactionalProxy extends AbstractTransactional
    {
        // should be set during doPrepare()
        protected SSTableReader finalReader;
        protected boolean openResult;
    }

    protected SSTableWriter(Descriptor descriptor,
                            long keyCount,
                            long repairedAt,
                            CFMetaData metadata,
                            MetadataCollector metadataCollector,
                            SerializationHeader header)
    {
        super(descriptor, components(metadata), metadata, DatabaseDescriptor.getDiskOptimizationStrategy());
        this.keyCount = keyCount;
        this.repairedAt = repairedAt;
        this.metadataCollector = metadataCollector;
        this.header = header != null ? header : SerializationHeader.makeWithoutStats(metadata); //null header indicates streaming from pre-3.0 sstable
        this.rowIndexEntrySerializer = descriptor.version.getSSTableFormat().getIndexSerializer(metadata, descriptor.version, header);
        this.observers = Collections.emptySet();
    }

    public static SSTableWriter create(Descriptor descriptor,
                                       Long keyCount,
                                       Long repairedAt,
                                       CFMetaData metadata,
                                       MetadataCollector metadataCollector,
                                       SerializationHeader header,
                                       LifecycleTransaction txn)
    {
        Factory writerFactory = descriptor.getFormat().getWriterFactory();
        return writerFactory.open(descriptor, keyCount, repairedAt, metadata, metadataCollector, header, txn);
    }

    public static SSTableWriter create(CFMetaData metadata,
                                       Descriptor descriptor,
                                       long keyCount,
                                       long repairedAt,
                                       int sstableLevel,
                                       SerializationHeader header,
                                       LifecycleTransaction txn)
    {
        MetadataCollector collector = new MetadataCollector(metadata.comparator).sstableLevel(sstableLevel);
        return create(descriptor, keyCount, repairedAt, metadata, collector, header, txn);
    }

    private static Set<Component> components(CFMetaData metadata)
    {
        Set<Component> components = new HashSet<Component>(Arrays.asList(Component.DATA,
                Component.PRIMARY_INDEX,
                Component.STATS,
                Component.SUMMARY,
                Component.TOC,
                Component.digestFor(BigFormat.latestVersion.uncompressedChecksumType())));

        if (metadata.params.bloomFilterFpChance < 1.0)
            components.add(Component.FILTER);

        if (metadata.params.compression.isEnabled())
        {
            components.add(Component.COMPRESSION_INFO);
        }
        else
        {
            // it would feel safer to actually add this component later in maybeWriteDigest(),
            // but the components are unmodifiable after construction
            components.add(Component.CRC);
        }
        return components;
    }

    public abstract void mark();

    /**
     * Appends partition data to this writer.
     *
     * @param iterator the partition to write
     * @return the created index entry if something was written, that is if {@code iterator}
     * wasn't empty, {@code null} otherwise.
     *
     * @throws FSWriteError if a write to the dataFile fails
     */
    public abstract RowIndexEntry append(UnfilteredRowIterator iterator);

    public abstract long getFilePointer();

    public abstract long getOnDiskFilePointer();

    public long getEstimatedOnDiskBytesWritten()
    {
        return getOnDiskFilePointer();
    }

    public SSTableWriter setRepairedAt(long repairedAt)
    {
        if (repairedAt > 0)
            this.repairedAt = repairedAt;
        return this;
    }

    public SSTableWriter setMaxDataAge(long maxDataAge)
    {
        this.maxDataAge = maxDataAge;
        return this;
    }

    public SSTableWriter setOpenResult(boolean openResult)
    {
        txnProxy.openResult = openResult;
        return this;
    }

    public SSTableReader finish(long repairedAt, long maxDataAge, boolean openResult)
    {
        if (repairedAt > 0)
            this.repairedAt = repairedAt;
        this.maxDataAge = maxDataAge;
        return finish(openResult);
    }

    public SSTableReader finish(boolean openResult)
    {
        setOpenResult(openResult);
        txnProxy.finish();
        observers.forEach(SSTableFlushObserver::complete);
        return finished();
    }

    /**
     * Open the resultant SSTableReader once it has been fully written, and all related state
     * is ready to be finalised including other sstables being written involved in the same operation
     */
    public SSTableReader finished()
    {
        return txnProxy.finalReader;
    }

    // finalise our state on disk, including renaming
    public final void prepareToCommit()
    {
        txnProxy.prepareToCommit();
    }

    public final Throwable commit(Throwable accumulate)
    {
        try
        {
            return txnProxy.commit(accumulate);
        }
        finally
        {
            observers.forEach(SSTableFlushObserver::complete);
        }
    }

    public final Throwable abort(Throwable accumulate)
    {
        return txnProxy.abort(accumulate);
    }

    public final void close()
    {
        txnProxy.close();
    }

    public final void abort()
    {
        txnProxy.abort();
    }

    protected Map<MetadataType, MetadataComponent> finalizeMetadata()
    {
        return metadataCollector.finalizeMetadata(getPartitioner().getClass().getCanonicalName(),
                                                  metadata.params.bloomFilterFpChance,
                                                  repairedAt,
                                                  header);
    }

    protected StatsMetadata statsMetadata()
    {
        return (StatsMetadata) finalizeMetadata().get(MetadataType.STATS);
    }

    public static abstract class Factory
    {
        public abstract SSTableWriter open(Descriptor descriptor,
                                           long keyCount,
                                           long repairedAt,
                                           CFMetaData metadata,
                                           MetadataCollector metadataCollector,
                                           SerializationHeader header,
                                           LifecycleTransaction txn);
    }
}
