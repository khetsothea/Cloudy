package com.bingzer.android.cloudy;

import android.database.Cursor;
import android.util.Log;

import com.bingzer.android.Timespan;
import com.bingzer.android.cloudy.contracts.ILocalConfiguration;
import com.bingzer.android.cloudy.contracts.IEntityHistory;
import com.bingzer.android.cloudy.contracts.ISyncEntity;
import com.bingzer.android.cloudy.contracts.ISyncManager;
import com.bingzer.android.cloudy.contracts.ISyncProvider;
import com.bingzer.android.dbv.IBaseEntity;
import com.bingzer.android.dbv.IEnvironment;
import com.bingzer.android.dbv.ITable;
import com.bingzer.android.dbv.queries.ISequence;
import com.bingzer.android.driven.LocalFile;
import com.bingzer.android.driven.RemoteFile;

import java.io.File;

class SyncProvider implements ISyncProvider {

    static final String TAG = "SyncProvider";
    static final int UPSTREAM = 1;
    static final int DOWNSTREAM = 2;

    private IEnvironment remote;
    private IEnvironment local;
    private ISyncManager manager;

    SyncProvider(ISyncManager manager, IEnvironment local, IEnvironment remote){
        this.manager = manager;
        this.local = local;
        this.remote = remote;
    }

    @Override
    public long sync(long timestamp) {
        Log.i(TAG, "Sync starting. LastSync: " + timestamp);
        long now = Timespan.now();
        TimeRange range = new TimeRange(timestamp, now);

        // update Local to Remote
        syncEnvironment(UPSTREAM, range, local, remote);
        // update Remote to Local
        syncEnvironment(DOWNSTREAM, range, remote, local);

        // update sync history
        syncEntityHistory(UPSTREAM, range, local, remote);
        syncEntityHistory(DOWNSTREAM, range, remote, local);

        // update client revision
        ILocalConfiguration config = LocalConfiguration.getConfig(local, LocalConfiguration.SETTING_CLIENTID);
        config.setValue(now);
        config.save();

        return now;
    }

    @Override
    public void cleanup() {
        File db = new File(remote.getDatabase().getPath());
        // cleanup
        remote.getDatabase().close();
        if(!db.delete())
            Log.e(TAG, "Cleanup() - failed to delete remote db");
    }

    /////////////////////////////////////////////////////////////////////////////////////////

    private void syncEnvironment(final int streamType, TimeRange range, final IEnvironment source, final IEnvironment destination){
        final IEntityHistory syncHistory = new EntityHistory(destination);
        source.getDatabase().get(IEntityHistory.TABLE_NAME).select("Timestamp >= ? AND Timestamp < ?", range.from, range.to)
                .orderBy("Timestamp")
                .query(new ISequence<Cursor>() {
                    @Override
                    public boolean next(Cursor cursor) {
                        return syncSequence(streamType, source, destination, syncHistory, cursor);
                    }
                });
    }

    private void syncEntityHistory(int type, TimeRange range, final IEnvironment source, final IEnvironment destination){
        final IEntityHistory syncHistory = new EntityHistory(destination);
        source.getDatabase().get(IEntityHistory.TABLE_NAME).select("Timestamp >= ? AND Timestamp < ?", range.from, range.to)
                .orderBy("Timestamp")
                .query(new ISequence<Cursor>() {
                    @Override
                    public boolean next(Cursor cursor) {
                        syncHistory.load(cursor);
                        if (!destination.getDatabase()
                                .get(IEntityHistory.TABLE_NAME)
                                .has("SyncId = ?", syncHistory.getSyncId())) {
                            syncHistory.setId(-1);
                            syncHistory.save();
                        }
                        return true;
                    }
                });
    }

    /////////////////////////////////////////////////////////////////////////////////////////

    private boolean syncSequence(final int streamType, final IEnvironment source, final IEnvironment destination, IEntityHistory syncHistory, Cursor cursor){
        syncHistory.load(cursor);

        SQLiteSyncBuilder builder = (SQLiteSyncBuilder)source.getDatabase().getBuilder();

        ITable sourceTable = source.getDatabase().get(syncHistory.getEntityName());
        ITable destinationTable = destination.getDatabase().get(syncHistory.getEntityName());

        IBaseEntity entity = builder.onEntityCreate(source, syncHistory.getEntityName());
        if(!(entity instanceof SyncEntity))
            throw new SyncException("Entity/Table " + entity.getTableName() + " is not an instanceof SyncEntity");
        ISyncEntity syncEntity = (ISyncEntity) entity;

        switch (syncHistory.getEntityAction()) {
            case IEntityHistory.INSERT:
                sourceTable.select("SyncId = ?", syncHistory.getEntitySyncId()).query(syncEntity);
                destinationTable.insert(syncEntity);

                create(streamType, syncEntity);
                break;
            case IEntityHistory.DELETE:
                destinationTable.select("SyncId = ?", syncHistory.getEntitySyncId()).query(syncEntity);
                destinationTable.delete("SyncId = ?", syncHistory.getEntitySyncId());

                delete(streamType, syncEntity);
                break;
            case IEntityHistory.UPDATE:
                sourceTable.select("SyncId = ?", syncHistory.getEntitySyncId()).query(syncEntity);

                ISyncEntity destEntity = builder.onEntityCreate(destination, syncHistory.getEntityName());
                destinationTable.select("SyncId = ?", syncHistory.getEntitySyncId()).query(destEntity);

                update(streamType, syncEntity, destEntity);
                destinationTable.update(syncEntity);
                break;
        }

        return true;
    }

    private void create(int streamType, ISyncEntity entity){
        switch (streamType){
            default:
                throw new SyncException("Unknown stream type: " + streamType);
            case UPSTREAM:
                createUpstream(entity);
                break;
            case DOWNSTREAM:
                createDownstream(entity);
                break;
        }
    }

    private void delete(int streamType, ISyncEntity entity){
        switch (streamType){
            default:
                throw new SyncException("Unknown stream type: " + streamType);
            case UPSTREAM:
                deleteUpstream(entity);
                break;
            case DOWNSTREAM:
                deleteDownstream(entity);
                break;
        }
    }

    private void update(int streamType, ISyncEntity srcEntity, ISyncEntity destEntity){
        switch (streamType){
            default:
                throw new SyncException("Unknown stream type: " + streamType);
            case UPSTREAM:
                updateUpstream(srcEntity, destEntity);
                break;
            case DOWNSTREAM:
                updateDownstream(srcEntity, destEntity);
                break;
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////////

    private void createUpstream(ISyncEntity entity){
        if(hasLocalFiles(entity)){
            RemoteFile remoteDir = getRemoteDirectory(entity);

            for(File file : entity.getLocalFiles()){
                LocalFile localFile = new LocalFile(file);
                String filename = createRemoteFileNameForLocalFile(entity, file);
                RemoteFile remoteFile = remoteDir.create(filename, localFile);
                if(remoteFile == null)
                    throw new SyncException("Unable to create RemoteFile: " + filename);
            }
        }
    }

    private void createDownstream(ISyncEntity entity){
        if(hasLocalFiles(entity)){
            RemoteFile remoteDir = getRemoteDirectory(entity);

            for(File file : entity.getLocalFiles()){
                LocalFile localFile = new LocalFile(file);
                String filename = createRemoteFileNameForLocalFile(entity, file);
                RemoteFile remoteFile = remoteDir.get(filename);
                if(!remoteFile.download(localFile))
                    throw new SyncException("Unable to download: " + file.getName());
            }
        }
    }

    private void deleteUpstream(ISyncEntity entity){
        if(hasLocalFiles(entity)){
            RemoteFile remoteDir = getRemoteDirectory(entity);

            for(File file : entity.getLocalFiles()){
                String filename = createRemoteFileNameForLocalFile(entity, file);
                RemoteFile remoteFile = remoteDir.get(filename);
                if(!remoteFile.delete())
                    throw new SyncException("Unable to delete: " + file.getName());
            }
        }
    }

    private void deleteDownstream(ISyncEntity entity){
        if(hasLocalFiles(entity)){

            for(File file : entity.getLocalFiles()){
                if(!file.delete())
                    throw new SyncException("Unable to delete: " + file);
            }
        }
    }

    private void updateUpstream(ISyncEntity srcEntity, ISyncEntity destEntity){
        if(hasLocalFiles(destEntity)){
            RemoteFile remoteDir = getRemoteDirectory(destEntity);

            for(File file : destEntity.getLocalFiles()){
                String filename = createRemoteFileNameForLocalFile(destEntity, file);
                remoteDir.get(filename).delete();
            }
        }

        if(hasLocalFiles(srcEntity)){
            RemoteFile remoteDir = getRemoteDirectory(srcEntity);

            for(File file : srcEntity.getLocalFiles()){
                LocalFile localFile = new LocalFile(file);
                String filename = createRemoteFileNameForLocalFile(srcEntity, file);
                remoteDir.create(filename, localFile);
            }
        }
    }

    private void updateDownstream(ISyncEntity srcEntity, ISyncEntity destEntity){
        if(hasLocalFiles(destEntity)){
            for(File file : destEntity.getLocalFiles()){
                file.delete();
            }
        }

        if(hasLocalFiles(srcEntity)){
            RemoteFile remoteDir = getRemoteDirectory(srcEntity);

            for(File file : srcEntity.getLocalFiles()){
                LocalFile localFile = new LocalFile(file);
                String filename = createRemoteFileNameForLocalFile(srcEntity, file);
                RemoteFile remoteFile = remoteDir.get(filename);
                remoteFile.download(localFile);
            }
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////////

    private RemoteFile getRemoteDirectory(ISyncEntity entity){
        RemoteFile remoteFile = manager.getRoot().get(entity.getTableName());
        if(remoteFile == null)
            remoteFile = manager.getRoot().create(entity.getTableName());

        if(remoteFile == null)
            throw new SyncException("Can't get or create remote directory for " + entity.getTableName());
        return remoteFile;
    }

    private String createRemoteFileNameForLocalFile(ISyncEntity entity, File file){
        return entity.getSyncId() + "." + file.getName();
    }

    private boolean hasLocalFiles(ISyncEntity entity){
        return entity.getLocalFiles() != null && entity.getLocalFiles().length > 0;
    }


    ////////////////////////////////////////////////////////////////////////////////////////////

    private static class TimeRange {
        long from;
        long to;
        TimeRange(long from, long to){
            this.from = from;
            this.to = to;
        }
    }
}
