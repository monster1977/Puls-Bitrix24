package ru.local.bitrixtasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Local task cache and durable mutation queue for offline work. */
final class LocalStore extends SQLiteOpenHelper {
    static final int VERSION=1;
    LocalStore(Context c) { super(c,"tasks_cache.db",null,VERSION); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE pending (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, task_id INTEGER NOT NULL, payload TEXT NOT NULL)");
    }
    public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {}
    synchronized List<Task> tasks() {
        ArrayList<Task> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("tasks",new String[]{"json"},null,null,null,null,"id DESC")) {
            while(c.moveToNext()) out.add(new Task(new JSONObject(c.getString(0))));
        } catch(Exception ignored) {}
        return out;
    }
    synchronized void replace(List<Task> tasks) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try { db.delete("tasks",null,null); for(Task t:tasks) upsert(db,t); db.setTransactionSuccessful(); } finally { db.endTransaction(); }
    }
    synchronized void upsert(Task t) { upsert(getWritableDatabase(),t); }
    private void upsert(SQLiteDatabase db,Task t) { ContentValues v=new ContentValues();v.put("id",t.id);v.put("json",t.toJson().toString());db.insertWithOnConflict("tasks",null,v,SQLiteDatabase.CONFLICT_REPLACE); }
    synchronized void remove(long id) { getWritableDatabase().delete("tasks","id=?",new String[]{Long.toString(id)}); }
    synchronized long localId() { Cursor c=getReadableDatabase().rawQuery("SELECT MIN(id) FROM tasks",null);try{long n=0;if(c.moveToFirst()&&!c.isNull(0))n=c.getLong(0);return n>=0?-1:n-1;}finally{c.close();} }
    synchronized void enqueue(String type,long taskId,JSONObject payload) { ContentValues v=new ContentValues();v.put("type",type);v.put("task_id",taskId);v.put("payload",payload.toString());getWritableDatabase().insert("pending",null,v); }
    synchronized List<Pending> pending() { ArrayList<Pending> out=new ArrayList<>();try(Cursor c=getReadableDatabase().query("pending",new String[]{"id","type","task_id","payload"},null,null,null,null,"id ASC")){while(c.moveToNext())out.add(new Pending(c.getLong(0),c.getString(1),c.getLong(2),new JSONObject(c.getString(3))));}catch(Exception ignored){}return out; }
    synchronized void removePending(long id) { getWritableDatabase().delete("pending","id=?",new String[]{Long.toString(id)}); }
    synchronized void clearAll() { SQLiteDatabase db=getWritableDatabase();db.delete("tasks",null,null);db.delete("pending",null,null); }
    static final class Pending { final long id,taskId; final String type; final JSONObject payload; Pending(long i,String t,long tid,JSONObject p){id=i;type=t;taskId=tid;payload=p;} }
}
