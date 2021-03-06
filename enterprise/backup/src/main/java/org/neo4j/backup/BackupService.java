/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.backup;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.neo4j.com.RequestContext;
import org.neo4j.com.Response;
import org.neo4j.com.monitor.RequestMonitor;
import org.neo4j.com.storecopy.ResponseUnpacker;
import org.neo4j.com.storecopy.ResponseUnpacker.TxHandler;
import org.neo4j.com.storecopy.StoreCopyClient;
import org.neo4j.com.storecopy.StoreWriter;
import org.neo4j.com.storecopy.TransactionCommittingResponseUnpacker;
import org.neo4j.consistency.ConsistencyCheckService;
import org.neo4j.consistency.checking.full.ConsistencyCheckIncompleteException;
import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.CancellationRequest;
import org.neo4j.helpers.Service;
import org.neo4j.helpers.Settings;
import org.neo4j.helpers.progress.ProgressListener;
import org.neo4j.helpers.progress.ProgressMonitorFactory;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.kernel.DefaultFileSystemAbstraction;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.InternalAbstractGraphDatabase;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.configuration.ConfigParam;
import org.neo4j.kernel.extension.KernelExtensionFactory;
import org.neo4j.kernel.impl.store.MismatchingStoreIdException;
import org.neo4j.kernel.impl.store.NeoStore;
import org.neo4j.kernel.impl.store.StoreId;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;
import org.neo4j.kernel.impl.transaction.log.MissingLogDataException;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.logging.ConsoleLogger;
import org.neo4j.kernel.logging.DevNullLoggingService;
import org.neo4j.kernel.logging.Logging;
import org.neo4j.kernel.monitoring.ByteCounterMonitor;
import org.neo4j.kernel.monitoring.Monitors;

import static org.neo4j.com.RequestContext.anonymous;

/**
 * Client-side convenience service for doing backups from a running database instance.
 */
class BackupService
{
    class BackupOutcome
    {
        private final boolean consistent;
        private final long lastCommittedTx;

        BackupOutcome( long lastCommittedTx, boolean consistent )
        {
            this.lastCommittedTx = lastCommittedTx;
            this.consistent = consistent;
        }

        public long getLastCommittedTx()
        {
            return lastCommittedTx;
        }

        public boolean isConsistent()
        {
            return consistent;
        }
    }

    static final String TOO_OLD_BACKUP = "It's been too long since this backup was last " + "updated, and it has " +
            "fallen too far behind the database transaction stream for incremental backup to be possible. You need to" +
            " perform a full backup at this point. " + "You can modify this time interval by setting the '" +
            GraphDatabaseSettings.keep_logical_logs.name() + "' configuration on the database to a higher value.";

    static final String DIFFERENT_STORE = "Target directory contains full backup of a logically different store.";

    private final FileSystemAbstraction fileSystem;
    private final StringLogger logger;

    BackupService()
    {
        this( new DefaultFileSystemAbstraction(), StringLogger.SYSTEM );
    }

    BackupService( FileSystemAbstraction fileSystem )
    {
        this( fileSystem, StringLogger.SYSTEM );
    }

    BackupService( FileSystemAbstraction fileSystem, StringLogger logger )
    {
        this.fileSystem = fileSystem;
        this.logger = logger;
    }

    BackupOutcome doFullBackup( final String sourceHostNameOrIp, final int sourcePort, String targetDirectory,
            boolean checkConsistency, Config tuningConfiguration, final long timeout )
    {
        if ( directoryContainsDb( targetDirectory ) )
        {
            throw new RuntimeException( targetDirectory + " already contains a database" );
        }
        Map<String, String> params = tuningConfiguration.getParams();
        params.put( GraphDatabaseSettings.store_dir.name(), targetDirectory );
        tuningConfiguration.applyChanges( params );
        long timestamp = System.currentTimeMillis();
        long lastCommittedTx = -1;
        boolean consistent = !checkConsistency; // default to true if we're not checking consistency
        GraphDatabaseAPI targetDb = null;
        try
        {
            StoreCopyClient storeCopier = new StoreCopyClient( tuningConfiguration, loadKernelExtensions(),
                    new ConsoleLogger( StringLogger.SYSTEM ), new DevNullLoggingService(), new DefaultFileSystemAbstraction() );
            storeCopier.copyStore( new StoreCopyClient.StoreCopyRequester()
            {
                private BackupClient client;

                @Override
                public Response<?> copyStore( StoreWriter writer )
                {
                    Monitors monitors = new Monitors();
                    client = new BackupClient( sourceHostNameOrIp, sourcePort, new DevNullLoggingService(),
                            StoreId.DEFAULT, timeout, ResponseUnpacker.NO_OP_RESPONSE_UNPACKER,
                            monitors.newMonitor( ByteCounterMonitor.class ),
                            monitors.newMonitor( RequestMonitor.class ) );
                    client.start();
                    return client.fullBackup( writer );
                }

                @Override
                public void done()
                {
                    client.stop();
                }
            }, CancellationRequest.NEVER_CANCELLED );

            targetDb = startTemporaryDb( targetDirectory );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
        finally
        {
            if ( targetDb != null )
            {
                targetDb.shutdown();
            }
        }
        bumpMessagesDotLogFile( targetDirectory, timestamp );
        if ( checkConsistency )
        {
            try
            {
                consistent = new ConsistencyCheckService().runFullConsistencyCheck( targetDirectory,
                        tuningConfiguration, ProgressMonitorFactory.textual( System.err ), logger ).isSuccessful();
            }
            catch ( ConsistencyCheckIncompleteException e )
            {
                logger.error( "Consistency check incomplete", e );
            }
            finally
            {
                logger.flush();
            }
        }
        return new BackupOutcome( lastCommittedTx, consistent );
    }

    BackupOutcome doIncrementalBackup( String sourceHostNameOrIp, int sourcePort, String targetDirectory,
            boolean verification, long timeout ) throws IncrementalBackupNotPossibleException
    {
        if ( !directoryContainsDb( targetDirectory ) )
        {
            throw new RuntimeException( targetDirectory + " doesn't contain a database" );
        }
        // In case someone deleted the logical log from a full backup
        ConfigParam keepLogs = new ConfigParam()
        {
            @Override
            public void configure( Map<String, String> config )
            {
                config.put( GraphDatabaseSettings.keep_logical_logs.name(), Settings.TRUE );
            }
        };
        GraphDatabaseAPI targetDb = startTemporaryDb( targetDirectory, keepLogs );
        long backupStartTime = System.currentTimeMillis();
        BackupOutcome outcome = null;
        try
        {
            outcome = doIncrementalBackup( sourceHostNameOrIp, sourcePort, targetDb, timeout );
        }
        finally
        {
            targetDb.shutdown();
        }
        bumpMessagesDotLogFile( targetDirectory, backupStartTime );
        return outcome;
    }

    BackupOutcome doIncrementalBackupOrFallbackToFull( String sourceHostNameOrIp, int sourcePort,
            String targetDirectory, boolean verification, Config config, long timeout )
    {
        if ( !directoryContainsDb( targetDirectory ) )
        {
            return doFullBackup( sourceHostNameOrIp, sourcePort, targetDirectory, verification, config, timeout );
        }
        try
        {
            return doIncrementalBackup( sourceHostNameOrIp, sourcePort, targetDirectory, verification, timeout );
        }
        catch ( IncrementalBackupNotPossibleException e )
        {
            try
            {
                // Our existing backup is out of date.
                logger.info( "Existing backup is too far out of date, a new full backup will be performed." );
                File targetDirFile = new File( targetDirectory );
                FileUtils.deleteRecursively( targetDirFile );
                return doFullBackup( sourceHostNameOrIp, sourcePort, targetDirFile.getAbsolutePath(), verification,
                        config, timeout );
            }
            catch ( Exception fullBackupFailure )
            {
                throw new RuntimeException( "Failed to perform incremental backup, fell back to full backup, "
                        + "but that failed as well: '" + fullBackupFailure.getMessage() + "'.", fullBackupFailure );
            }
        }
    }

    BackupOutcome doIncrementalBackup( String sourceHostNameOrIp, int sourcePort, GraphDatabaseAPI targetDb, long timeout )
            throws IncrementalBackupNotPossibleException
    {
        return incrementalWithContext( sourceHostNameOrIp, sourcePort, targetDb, timeout, slaveContextOf( targetDb ) );
    }

    private RequestContext slaveContextOf( GraphDatabaseAPI graphDb )
    {
        TransactionIdStore transactionIdStore =
                graphDb.getDependencyResolver().resolveDependency( TransactionIdStore.class );
        return anonymous( transactionIdStore.getLastCommittedTransactionId() );
    }

    boolean directoryContainsDb( String targetDirectory )
    {
        return fileSystem.fileExists( new File( targetDirectory, NeoStore.DEFAULT_NAME ) );
    }

    static GraphDatabaseAPI startTemporaryDb( String targetDirectory, ConfigParam... params )
    {
        Map<String, String> config = new HashMap<>();
        config.put( OnlineBackupSettings.online_backup_enabled.name(), Settings.FALSE );
        config.put( InternalAbstractGraphDatabase.Configuration.log_configuration_file.name(),
                "neo4j-backup-logback.xml" );
        for ( ConfigParam param : params )
        {
            if ( param != null )
            {
                param.configure( config );
            }
        }
        return (GraphDatabaseAPI) new GraphDatabaseFactory().newEmbeddedDatabaseBuilder( targetDirectory )
                .setConfig( config ).newGraphDatabase();
    }

    /**
     * Performs an incremental backup based off the given context. This means
     * receiving and applying selectively (i.e. irrespective of the actual state
     * of the target db) a set of transactions starting at the desired txId and
     * spanning up to the latest of the master
     *
     * @param targetDb The database that contains a previous full copy
     * @param context  The context, containing transaction id to start streaming transaction from
     * @return A backup context, ready to perform
     */
    private BackupOutcome incrementalWithContext( String sourceHostNameOrIp, int sourcePort, GraphDatabaseAPI targetDb,
                                                  long timeout, RequestContext context ) throws IncrementalBackupNotPossibleException
    {
        DependencyResolver resolver = targetDb.getDependencyResolver();

        ProgressTxHandler handler = new ProgressTxHandler();
        TransactionCommittingResponseUnpacker unpacker = new TransactionCommittingResponseUnpacker( resolver );

        Monitors monitors = resolver.resolveDependency( Monitors.class );
        BackupClient client = new BackupClient( sourceHostNameOrIp, sourcePort,
                resolver.resolveDependency( Logging.class ), targetDb.storeId(), timeout, unpacker,
                monitors.newMonitor( ByteCounterMonitor.class, BackupClient.class ),
                monitors.newMonitor( RequestMonitor.class, BackupClient.class ) );

        boolean consistent = false;
        try
        {
            client.start();
            unpacker.start();

            unpacker.unpackResponse( client.incrementalBackup( context ), handler );

            consistent = true;
        }
        catch ( MismatchingStoreIdException e )
        {
            throw new RuntimeException( DIFFERENT_STORE, e );
        }
        catch ( RuntimeException | IOException e )
        {
            if ( e.getCause() != null && e.getCause() instanceof MissingLogDataException )
            {
                throw new IncrementalBackupNotPossibleException( TOO_OLD_BACKUP, e.getCause() );
            }
            throw new RuntimeException( "Failed to perform incremental backup.", e );
        }
        catch ( Throwable throwable )
        {
            throw new RuntimeException( "Unexpected error", throwable );
        }
        finally
        {
            try
            {
                client.stop();
                unpacker.stop();
            }
            catch ( Throwable throwable )
            {
                logger.warn( "Unable to stop backup client", throwable );
            }
        }
        return new BackupOutcome( handler.getLastSeenTransactionId(), consistent );
    }

    private static boolean bumpMessagesDotLogFile( String targetDirectory, long toTimestamp )
    {
        File dbDirectory = new File( targetDirectory );
        File[] candidates = dbDirectory.listFiles( new FilenameFilter()
        {
            @Override
            public boolean accept( File dir, String name )
            {
                /*
                 *  Contains ensures that previously timestamped files are
                 *  picked up as well
                 */
                return name.equals( StringLogger.DEFAULT_NAME );
            }
        } );
        if ( candidates.length != 1 )
        {
            return false;
        }
        // candidates has a unique member, the right one
        File previous = candidates[0];
        // Build to, from existing parent + new filename
        File to = new File( previous.getParentFile(), StringLogger.DEFAULT_NAME + "." + toTimestamp );
        return previous.renameTo( to );
    }

    private List<KernelExtensionFactory<?>> loadKernelExtensions()
    {
        List<KernelExtensionFactory<?>> kernelExtensions = new ArrayList<>();
        for ( KernelExtensionFactory<?> factory : Service.load( KernelExtensionFactory.class ) )
        {
            kernelExtensions.add( factory );
        }
        return kernelExtensions;
    }

    private static class ProgressTxHandler implements TxHandler
    {
        private final ProgressListener progress = ProgressMonitorFactory.textual( System.out ).openEnded(
                "Transactions applied", 1000 );
        private long lastSeenTransactionId;

        @Override
        public void accept( CommittedTransactionRepresentation tx )
        {
            progress.add( 1 );
            lastSeenTransactionId = tx.getCommitEntry().getTxId();
        }

        @Override
        public void done()
        {
            progress.done();
        }

        public long getLastSeenTransactionId()
        {
            return lastSeenTransactionId;
        }
    }
}
