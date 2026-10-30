package ru.local.bitrixtasks;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,34})
public class LocalStoreTest {
    @Test public void taskCacheAndPendingQueueSurviveStoreOperations() throws Exception {
        LocalStore store=new LocalStore(RuntimeEnvironment.getApplication()); store.clearAll();
        Task task=new Task(new JSONObject().put("id",-1).put("title","Офлайн задача").put("description","x").put("status",2).put("responsibleId",1).put("deadline","").put("priority",1));
        store.upsert(task); assertEquals(1,store.tasks().size()); assertEquals("Офлайн задача",store.tasks().get(0).title);
        store.enqueue("ADD",-1,new JSONObject().put("title",task.title)); assertEquals(1,store.pending().size());
        store.clearAll(); assertTrue(store.tasks().isEmpty()); assertTrue(store.pending().isEmpty()); store.close();
    }
}
