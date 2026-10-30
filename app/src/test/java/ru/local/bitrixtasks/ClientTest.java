package ru.local.bitrixtasks;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientTest {
    @Test public void acceptsWebhookWithOptionalTrailingSlash() {
        Webhook w=Webhook.parse(" https://team.bitrix24.ru/rest/42/test_key-1 ");
        assertEquals(42,w.userId);
        assertEquals("https://team.bitrix24.ru/rest/42/test_key-1/",w.baseUrl);
        assertFalse(w.toString().contains("test_key"));
    }
    @Test public void rejectsUnsafeOrMalformedEndpointsWithoutLeakingSecrets() {
        String[] inputs={"http://a.ru/rest/1/SECRET/","https://u:SECRET@a.ru/rest/1/token/",
                "https://a.ru/rest/1/SECRET/?a=1","https://a.ru/rest/1/SECRET/#x",
                "https://a.ru:8443/rest/1/SECRET/","https://a.ru/rest/0/SECRET/",
                "https://a.ru/rest/1/SECRET/tasks.task.list","https://a.ru/rest/1/%2E%2E/",
                "https://a.ru/rest/99999999999999999999/SECRET/","", "https://a.ru/rest/1/SECRET/../"};
        for(String input:inputs) {
            try { Webhook.parse(input); fail("Accepted malformed URL"); }
            catch(IllegalArgumentException e) { assertFalse(e.getMessage().contains("SECRET")); }
        }
    }
    @Test public void listUsesRealStatusAndOwnerAndServerSearch() throws Exception {
        BitrixApi api=new BitrixApi((method,p)->{
            assertEquals("tasks.task.list",method);
            assertEquals(50,p.getInt("start"));
            JSONObject f=p.getJSONObject("filter");
            assertEquals(42,f.getLong("RESPONSIBLE_ID"));
            assertEquals(5,f.getInt("!REAL_STATUS"));
            assertEquals("план",f.getString("%TITLE"));
            return new JSONObject().put("result",new JSONObject().put("tasks",new JSONArray().put(task(1,2)))).put("next",100);
        });
        BitrixApi.Page page=api.list(50," план ",0,true,42);
        assertEquals(100,page.next); assertEquals(1,page.tasks.size());
    }
    @Test public void doneAndAllTabsHaveCorrectFilters() throws Exception {
        BitrixApi done=new BitrixApi((method,p)->{
            assertEquals(5,p.getJSONObject("filter").getInt("REAL_STATUS"));
            assertFalse(p.getJSONObject("filter").has("RESPONSIBLE_ID"));
            return page();
        });
        assertEquals(-1,done.list(0,"",1,false,1).next);
        BitrixApi all=new BitrixApi((method,p)->{assertEquals(0,p.getJSONObject("filter").length());return page();});
        all.list(0,"",2,false,1);
    }
    @Test public void paginationFallsBackToTotalAndRejectsBackwardCursor() throws Exception {
        JSONObject response=page();response.getJSONObject("result").getJSONArray("tasks").put(task(1,2));response.put("total",3);
        assertEquals(1,new BitrixApi((m,p)->response).list(0,"",2,false,1).next);
        response.put("next",0);
        assertEquals(-1,new BitrixApi((m,p)->response).list(0,"",2,false,1).next);
    }
    @Test public void createsTaskWithTimezoneAndResponsible() throws Exception {
        BitrixApi api=new BitrixApi((method,p)->{
            assertEquals("tasks.task.add",method);
            JSONObject f=p.getJSONObject("fields");
            assertEquals("Новая",f.getString("TITLE"));
            assertEquals(7,f.getLong("RESPONSIBLE_ID"));
            assertEquals("2030-01-01T18:00:00+05:00",f.getString("DEADLINE"));
            assertEquals(2,f.getInt("PRIORITY"));
            return new JSONObject().put("result",new JSONObject().put("task",task(9,2)));
        });
        assertEquals(9,api.add("Новая","Описание",7,"2030-01-01T18:00:00+05:00",true).id);
    }
    @Test public void emptyDeadlineIsOmitted() throws Exception {
        new BitrixApi((method,p)->{
            assertFalse(p.getJSONObject("fields").has("DEADLINE"));
            return new JSONObject().put("result",new JSONObject().put("task",task(1,2)));
        }).add("title","",1,"",false);
    }
    @Test public void completionHonorsStatusReturnedByPortal() throws Exception {
        Task done=new BitrixApi((method,p)->{
            assertEquals("tasks.task.complete",method); assertEquals(11,p.getLong("taskId"));
            return new JSONObject().put("result",new JSONObject().put("task",task(11,4)));
        }).complete(11);
        assertEquals("На контроле",done.statusLabel());
    }
    @Test public void nullDeadlinesAndMissingPersonAreSupported() throws Exception {
        Task t=new Task(task(1,2).put("deadline",JSONObject.NULL).put("description",JSONObject.NULL));
        assertEquals("Без срока",t.dateLabel());assertFalse(t.overdue());assertEquals("",t.description);assertEquals("Сотрудник #1",t.responsibleName);
    }
    @Test public void overdueExcludesCompletedAndAwaitingReview() throws Exception {
        JSONObject j=task(1,2).put("deadline","2000-01-01T12:00:00+05:00");
        assertTrue(new Task(j).overdue());assertFalse(new Task(j.put("status",5)).overdue());assertFalse(new Task(j.put("status",4)).overdue());
    }
    @Test public void actionPermissionsDisableCompletion() throws Exception {
        assertFalse(new Task(task(1,3).put("action",new JSONObject().put("complete",false))).canComplete);
        assertFalse(new Task(task(1,5)).canComplete);
    }
    @Test public void errorsAreActionableAndDoNotEchoServerSecrets() {
        assertTrue(BitrixApi.apiError("insufficient_scope").contains("прав"));
        assertTrue(BitrixApi.apiError("INVALID_CREDENTIALS").contains("Вебхук"));
        assertTrue(BitrixApi.apiError("QUERY_LIMIT_EXCEEDED").contains("Лимит"));
        assertFalse(BitrixApi.apiError("https://portal/rest/1/SECRET/").contains("SECRET"));
    }
    @Test public void updatesOnlyDeadlineAndUsesServerValue() throws Exception {
        String requested="2030-01-02T10:15:00+05:00", actual="2030-01-02T12:00:00+05:00";
        Task updated=new BitrixApi((method,p)->{
            assertEquals("tasks.task.update",method);assertEquals(17,p.getLong("taskId"));
            JSONObject fields=p.getJSONObject("fields");assertEquals(1,fields.length());
            assertEquals(requested,fields.getString("DEADLINE"));
            return new JSONObject().put("result",new JSONObject().put("task",task(17,3).put("deadline",actual)));
        }).updateDeadline(17,requested);
        assertEquals(actual,updated.deadline);assertEquals(3,updated.status);
    }
    @Test public void updatesOnlyTitle() throws Exception {
        Task updated=new BitrixApi((method,p)->{
            assertEquals("tasks.task.update",method); assertEquals(17,p.getLong("taskId"));
            assertEquals("Новое название",p.getJSONObject("fields").getString("TITLE"));
            assertEquals(1,p.getJSONObject("fields").length());
            return new JSONObject().put("result",new JSONObject().put("task",task(17,2).put("title","Новое название")));
        }).updateTitle(17,"Новое название");
        assertEquals("Новое название",updated.title);
    }
    @Test public void readsChecklistItemsFromTask() throws Exception {
        Task t=new Task(task(4,2).put("checklist",new JSONArray().put(new JSONObject().put("title","Проверить договор").put("isComplete",true)).put(new JSONObject().put("TITLE","Отправить клиенту").put("IS_COMPLETE",0))));
        assertEquals(2,t.checklist.size()); assertTrue(t.checklist.get(0).complete); assertEquals("Отправить клиенту",t.checklist.get(1).title);
    }
    @Test public void getsFullTaskForChecklist() throws Exception {
        Task t=new BitrixApi((method,p)->{
            if(method.equals("tasks.task.get")) return new JSONObject().put("result",new JSONObject().put("task",task(4,2)));
            assertEquals("task.checklistitem.getlist",method); assertEquals(4,p.getLong("TASKID"));
            return new JSONObject().put("result",new JSONArray().put(new JSONObject().put("TITLE","Проверить").put("IS_COMPLETE","Y")));
        }).getTask(4);
        assertEquals(1,t.checklist.size()); assertEquals("Проверить",t.checklist.get(0).title);
        assertTrue(t.checklist.get(0).complete);
    }
    @Test public void clearingDeadlineSendsExplicitEmptyField() throws Exception {
        Task updated=new BitrixApi((method,p)->{
            assertEquals("",p.getJSONObject("fields").getString("DEADLINE"));
            return new JSONObject().put("result",new JSONObject().put("task",task(17,3).put("deadline",JSONObject.NULL)));
        }).updateDeadline(17,"");
        assertEquals("",updated.deadline);
    }
    @Test public void invalidDeadlineIsRejectedBeforeRequest() throws Exception {
        try {
            new BitrixApi((method,p)->{fail("Must not call the portal");return null;}).updateDeadline(1,"invalid");
            fail("Invalid deadline accepted");
        }catch(java.time.format.DateTimeParseException expected) {}
    }
    private static JSONObject task(long id,int status) throws Exception {return new JSONObject().put("id",Long.toString(id)).put("title","Задача").put("status",Integer.toString(status)).put("responsibleId","1");}
    private static JSONObject page() throws Exception {return new JSONObject().put("result",new JSONObject().put("tasks",new JSONArray()));}
}
