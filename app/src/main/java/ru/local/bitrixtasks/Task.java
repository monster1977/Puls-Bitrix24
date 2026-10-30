package ru.local.bitrixtasks;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class Task {
    final long id, responsibleId;
    final String title, description, deadline, responsibleName;
    final List<ChecklistItem> checklist;
    final int status;
    final boolean canComplete, highPriority;
    Task(JSONObject j) {
        id = j.optLong("id"); title = j.optString("title", "Без названия");
        description = clean(j, "description"); deadline = clean(j, "deadline");
        responsibleId = j.optLong("responsibleId"); status = j.optInt("status", 2);
        JSONObject person = j.optJSONObject("responsible"), action = j.optJSONObject("action");
        responsibleName = person == null ? "Сотрудник #" + responsibleId : person.optString("name", "Сотрудник #" + responsibleId);
        canComplete = status != 5 && (action == null || action.optBoolean("complete", false));
        highPriority = j.optInt("priority") == 2;
        checklist = readChecklist(j);
    }
    private static String clean(JSONObject j, String key) { return j.isNull(key) ? "" : j.optString(key, ""); }
    private static List<ChecklistItem> readChecklist(JSONObject j) {
        ArrayList<ChecklistItem> out=new ArrayList<>(); JSONArray a=j.optJSONArray("checklist"); if(a==null)a=j.optJSONArray("checkLists");
        if(a==null)return out;
        for(int i=0;i<a.length();i++){JSONObject item=a.optJSONObject(i);if(item!=null){String title=item.optString("title",item.optString("TITLE",""));if(!title.isEmpty())out.add(new ChecklistItem(title,completed(item)));}}
        return out;
    }
    private static boolean completed(JSONObject item) {
        if(item.has("isComplete")) return item.optBoolean("isComplete",false);
        Object value=item.opt("IS_COMPLETE");
        if(value instanceof Boolean) return (Boolean)value;
        if(value instanceof Number) return ((Number)value).intValue()!=0;
        return "Y".equalsIgnoreCase(String.valueOf(value));
    }
    static final class ChecklistItem { final String title; final boolean complete; ChecklistItem(String t,boolean c){title=t;complete=c;} }
    JSONObject toJson() {
        try { JSONArray items=new JSONArray();for(ChecklistItem item:checklist)items.put(new JSONObject().put("title",item.title).put("isComplete",item.complete)); return new JSONObject().put("id",id).put("title",title).put("description",description).put("responsibleId",responsibleId).put("status",status).put("deadline",deadline).put("priority",highPriority?2:1).put("responsible",new JSONObject().put("name",responsibleName)).put("checklist",items); }
        catch(Exception e) { return new JSONObject(); }
    }
    boolean overdue() {
        if (status == 5 || status == 4 || deadline.isEmpty()) return false;
        try { return OffsetDateTime.parse(deadline).toInstant().isBefore(java.time.Instant.now()); }
        catch (Exception e) { return false; }
    }
    String dateLabel() {
        if (deadline.isEmpty()) return "Без срока";
        try {
            return OffsetDateTime.parse(deadline).atZoneSameInstant(ZonedDateTime.now().getZone())
                    .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", new Locale("ru")));
        } catch (Exception e) { return deadline; }
    }
    String statusLabel() {
        switch (status) {
            case 2: return "Ждёт выполнения";
            case 3: return "В работе";
            case 4: return "На контроле";
            case 5: return "Завершена";
            case 6: return "Отложена";
            case 7: return "Отклонена";
            default: return "Новая";
        }
    }
}
