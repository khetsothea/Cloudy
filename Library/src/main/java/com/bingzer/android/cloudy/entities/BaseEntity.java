package com.bingzer.android.cloudy.entities;

import android.database.Cursor;

import com.bingzer.android.cloudy.Environment;
import com.bingzer.android.cloudy.contracts.IBaseEntity;
import com.bingzer.android.cloudy.contracts.IEnvironment;
import com.bingzer.android.cloudy.utils.UniqueId;
import com.bingzer.android.dbv.Delegate;
import com.bingzer.android.dbv.ITable;
import com.bingzer.android.dbv.utils.EntityUtils;

abstract class BaseEntity implements IBaseEntity {

    private long id = -1;
    private long syncId = -1;
    protected IEnvironment environment;

    //////////////////////////////////////////////////////////////////////////////////////////

    public BaseEntity(){
        this(Environment.getDefault());
    }

    BaseEntity(IEnvironment environment){
        this.environment = environment;
    }

    //////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public final void setId(long id) {
        this.id = id;
    }

    @Override
    public final long getId() {
        return id;
    }

    @Override
    public final long getSyncId() {
        return syncId;
    }

    @Override
    public final void setSyncId(long syncId) {
        this.syncId = syncId;
    }

    /////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public final void save(){
        if(id > 0) {
            // UPDATED
            onPreUpdated();
            environment.getDatabase().get(getTableName()).update(this);

            SyncHistory.update(this);
            onPostUpdated();
        }
        else {
            // INSERTED
            onPreInserted();
            syncId = UniqueId.generateUniqueId();
            id = environment.getDatabase().get(getTableName()).insert(this).query();

            SyncHistory.insert(this);
            onPostInserted();
        }
    }

    @Override
    public final void delete(){
        if(id > 0){
            // DELETED
            onPreDeleted();
            environment.getDatabase().get(getTableName()).delete(this);

            SyncHistory.delete(this);
            onPostDeleted();
        }
    }

    @Override
    public final void load(){
        load(getId());
    }

    @Override
    public final void load(long id){
        environment.getDatabase().get(getTableName()).select(id).query(this);
    }

    @Override
    public final void loadBySyncId(){
        load(getSyncId());
    }

    @Override
    public final void loadBySyncId(long syncId){
        environment.getDatabase().get(getTableName()).select("SyncId = ?", syncId).query(this);
    }

    @Override
    public final void load(Cursor cursor){
        ITable table = environment.getDatabase().get(getTableName());
        EntityUtils.mapEntityFromCursor(table, this, cursor);
    }

    /////////////////////////////////////////////////////////////////////////////////////////

    protected void onPreInserted(){
        // placeholder
    }

    protected void onPostInserted(){
        // placeholder
    }

    protected void onPreUpdated(){
        // placeholder
    }

    protected void onPostUpdated(){
        // placeholder
    }

    protected void onPreDeleted(){
        // placeholder
    }

    protected void onPostDeleted(){
        // placeHolder
    }

    /////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public IEnvironment getEnvironment(){
        return environment;
    }

    protected ITable getTable(){
        return environment.getDatabase().get(getTableName());
    }

    /////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void map(Mapper mapper) {
        mapper.mapId(new Delegate.TypeId(this) {
            @Override
            public void set(Long value) {
                setId(value);
            }
        });
        mapper.map("SyncId", new Delegate.TypeLong() {
            @Override
            public void set(Long aLong) {
                setSyncId(aLong);
            }

            @Override
            public Long get() {
                return getSyncId();
            }
        });
    }

}
