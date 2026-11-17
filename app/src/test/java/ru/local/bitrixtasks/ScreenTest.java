package ru.local.bitrixtasks;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.widget.TimePicker;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 34})
public class ScreenTest {
    @Test public void demoCreateCompleteAndFilterFlow() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Посмотреть демоверсию");
            assertNotNull(find(activity,"Согласовать план проекта"));
            click(activity,"＋  Новая задача");
            EditText title=(EditText)findHint(activity.getWindow().getDecorView(),"Что нужно сделать?");
            assertNotNull(title);title.setText("Проверка приложения");
            click(activity,"Создать задачу");
            assertNotNull(find(activity,"Задача создана в деморежиме"));
            click(activity,"✓  Завершить задачу");
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(find(activity,"Задача завершена в деморежиме"));
            click(activity,"←  К списку");
            assertNull(find(activity,"Проверка приложения"));
            click(activity,"Готово");
            assertNotNull(find(activity,"Проверка приложения"));
        }
    }
    @Test public void invalidWebhookShowsValidationWithoutConnecting() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Подключить портал  →");
            assertNotNull(find(activity,"Нужен полный HTTPS-адрес вебхука: https://портал/rest/ID/ключ/"));
        }
    }
    @Test public void adbWebhookIntentNeedsDebugBuildAndTrimsValue() {
        Intent typed=new Intent();
        typed.putExtra(MainActivity.WEBHOOK_EXTRA,"  https://portal.example/rest/97/secret/  ");
        assertEquals("https://portal.example/rest/97/secret/", MainActivity.webhookFromIntent(typed, true));
        assertNull(MainActivity.webhookFromIntent(typed, false));
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            assertTrue((controller.get().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);
        }
        assertNull(MainActivity.webhookFromIntent(new Intent(), true));
        Intent viewed=new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://portal.example/rest/97/secret/"));
        assertEquals("https://portal.example/rest/97/secret/", MainActivity.webhookFromIntent(viewed, true));
    }
    @Test public void emptyTitleDoesNotCreateTask() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Посмотреть демоверсию");click(activity,"＋  Новая задача");click(activity,"Создать задачу");
            assertNotNull(find(activity,"Укажите название задачи."));
        }
    }
    @Test public void demoChecklistItemsToggleInTaskDetail() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Посмотреть демоверсию");
            ((View)find(activity,"Согласовать план проекта").getParent()).performClick();
            MainActivity.Model model=(MainActivity.Model)activity.onRetainNonConfigurationInstance();
            assertEquals(3,model.selected.checklist.size());
            assertNotNull(find(activity,"✓  Согласовать смету с заказчиком"));
            click(activity,"✓  Согласовать смету с заказчиком");
            assertNotNull(find(activity,"Отметка снята в деморежиме"));
            assertNotNull(find(activity,"○  Согласовать смету с заказчиком"));
            click(activity,"○  Утвердить этапы работ");
            assertNotNull(find(activity,"Пункт чек-листа выполнен в деморежиме"));
            assertTrue(model.selected.checklist.get(1).complete);
            assertFalse(model.selected.checklist.get(0).complete);
            click(activity,"←  К списку");
            ((View)find(activity,"Согласовать план проекта").getParent()).performClick();
            assertTrue(model.selected.checklist.get(1).complete);
            assertFalse(model.selected.checklist.get(0).complete);
            assertNotNull(find(activity,"✓  Утвердить этапы работ"));
        }
    }
    @Test public void settingsSaveDefaultDeadlineInDemo() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get(); click(activity,"Посмотреть демоверсию"); click(activity,"☰");
            assertNotNull(find(activity,"Настройки")); click(activity,"Завтра");
            click(activity,"Сохранить настройки");
            MainActivity.Model model=(MainActivity.Model)activity.onRetainNonConfigurationInstance();
            assertEquals("tasks",model.screen); assertNotNull(find(activity,"Настройки сохранены"));
            click(activity,"＋  Новая задача");
            assertTrue(find(activity,"Выбрать дату и время")==null);
        }
    }
    @Test public void deadlineCanBeChangedClearedAndCancelled() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Посмотреть демоверсию");
            ((View)find(activity,"Согласовать план проекта").getParent()).performClick();
            MainActivity.Model model=(MainActivity.Model)activity.onRetainNonConfigurationInstance();
            String original=model.selected.deadline;
            click(activity,"Изменить крайний срок");
            assertFalse(find(activity,"Сохранить срок").isEnabled());
            chooseDate(activity,2030,0,2,10,15);
            assertEquals(original,model.selected.deadline);
            click(activity,"Отмена");assertEquals(original,model.selected.deadline);
            click(activity,"Изменить крайний срок");chooseDate(activity,2030,0,2,10,15);
            click(activity,"Сохранить срок");
            assertNotNull(find(activity,"Крайний срок обновлён в деморежиме"));
            assertEquals(2030,java.time.OffsetDateTime.parse(model.selected.deadline).getYear());
            assertEquals(15,java.time.OffsetDateTime.parse(model.selected.deadline).getMinute());
            assertEquals(model.selected.deadline,deadlineOf(model,104));
            click(activity,"Изменить крайний срок");click(activity,"Убрать срок");
            assertFalse(model.selected.deadline.isEmpty());
            click(activity,"Сохранить срок");assertEquals("",model.selected.deadline);
            assertEquals("",deadlineOf(model,104));
            assertNotNull(find(activity,"Крайний срок снят в деморежиме"));
        }
    }
    @Test public void deadlineFailureKeepsOriginalAndAllowsRetry() throws Exception {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();click(activity,"Посмотреть демоверсию");
            ((View)find(activity,"Согласовать план проекта").getParent()).performClick();
            MainActivity.Model model=(MainActivity.Model)activity.onRetainNonConfigurationInstance();
            String original=model.selected.deadline;
            model.demo=false;model.hook=Webhook.parse("https://example.com/rest/1/test/");
            model.api=new BitrixApi((method,p)->{throw new BitrixApi.ApiException("Нет права менять срок");});
            click(activity,"Изменить крайний срок");click(activity,"Убрать срок");click(activity,"Сохранить срок");
            for(int i=0;i<100 && model.busy;i++){Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idle();}
            assertFalse(model.busy);assertEquals(original,model.selected.deadline);
            assertEquals(original,deadlineOf(model,104));
            assertNotNull(find(activity,"Нет права менять срок"));
            assertTrue(find(activity,"Сохранить срок").isEnabled());
        }
    }
    @Test public void cancellingTimePickerDoesNotChangeDeadlineDraft() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();click(activity,"Посмотреть демоверсию");
            ((View)find(activity,"Согласовать план проекта").getParent()).performClick();
            MainActivity.Model model=(MainActivity.Model)activity.onRetainNonConfigurationInstance();
            String original=model.selected.deadline;click(activity,"Изменить крайний срок");
            click(activity,"Выбрать дату и время");
            DatePickerDialog date=(DatePickerDialog)ShadowAlertDialog.getLatestAlertDialog();date.updateDate(2030,0,2);
            date.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(original,model.editedDeadline);assertEquals(original,model.selected.deadline);
            activity.onBackPressed();assertEquals("detail",model.screen);
        }
    }
    @Test public void titleEditorEnablesSaveOnlyAfterActualChange() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Посмотреть демоверсию");
            ((View)find(activity,"Согласовать план проекта").getParent()).performClick();
            click(activity,"Изменить название");
            assertFalse(find(activity,"Сохранить название").isEnabled());
            EditText field=(EditText)findHint(activity.getWindow().getDecorView(),"Название");
            assertNotNull(field);
            field.setText("   ");
            assertFalse(find(activity,"Сохранить название").isEnabled());
            field.setText("Согласовать план проекта заново");
            assertTrue(find(activity,"Сохранить название").isEnabled());
            field.setText("Согласовать план проекта");
            assertFalse(find(activity,"Сохранить название").isEnabled());
        }
    }
    @Test public void titleSaveSendsOnlyTitleFieldAndUpdatesTask() throws Exception {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Посмотреть демоверсию");
            ((View)find(activity,"Согласовать план проекта").getParent()).performClick();
            MainActivity.Model model=(MainActivity.Model)activity.onRetainNonConfigurationInstance();
            java.util.List<String> calls=new java.util.ArrayList<>();
            model.demo=false; model.hook=Webhook.parse("https://example.com/rest/1/test/");
            model.api=new BitrixApi((method,payload)->{
                JSONObject fields=payload.getJSONObject("fields");
                calls.add(method+"|"+payload.optLong("taskId")+"|"+fields.optString("TITLE")+"|"+fields.length());
                return new JSONObject().put("result",new JSONObject().put("task",new JSONObject()
                        .put("id",payload.optLong("taskId")).put("title",fields.optString("TITLE"))
                        .put("status",2).put("responsibleId",1).put("priority",1)));
            });
            click(activity,"Изменить название");
            EditText field=(EditText)findHint(activity.getWindow().getDecorView(),"Название");
            field.setText("Новое название задачи");
            click(activity,"Сохранить название");
            for(int i=0;i<100 && model.busy;i++){Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idle();}
            assertFalse(model.busy);
            assertNotNull(find(activity,"Название обновлено в Bitrix24"));
            assertEquals("Новое название задачи",model.selected.title);
            assertEquals(1,calls.size());
            assertEquals("tasks.task.update|104|Новое название задачи|1",calls.get(0));
            assertEquals("Новое название задачи",titleOf(model,104));
        }
    }
    @Test public void titleSaveQueuesOfflineAndSyncsOnRefresh() throws Exception {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Посмотреть демоверсию");
            ((View)find(activity,"Согласовать план проекта").getParent()).performClick();
            MainActivity.Model model=(MainActivity.Model)activity.onRetainNonConfigurationInstance();
            java.util.List<String> calls=new java.util.ArrayList<>();
            java.util.concurrent.atomic.AtomicInteger updates=new java.util.concurrent.atomic.AtomicInteger();
            model.demo=false; model.hook=Webhook.parse("https://example.com/rest/1/test/");
            try { java.lang.reflect.Field f=MainActivity.class.getDeclaredField("local"); f.setAccessible(true); ((LocalStore)f.get(activity)).tasks(); } catch(Exception ignored) {} // warm the cache on the main thread so the io thread does not stall on first use
            model.api=new BitrixApi((method,payload)->{
                calls.add(method);
                if(method.equals("tasks.task.update")) {
                    if(updates.incrementAndGet()==1) throw new java.io.IOException("нет сети");
                    return new JSONObject().put("result",new JSONObject().put("task",new JSONObject()
                            .put("id",payload.optLong("taskId")).put("title",payload.getJSONObject("fields").optString("TITLE"))
                            .put("status",2).put("responsibleId",1).put("priority",1)));
                }
                return new JSONObject().put("result",new JSONObject().put("tasks",new JSONArray()));
            });
            click(activity,"Изменить название");
            EditText field=(EditText)findHint(activity.getWindow().getDecorView(),"Название");
            field.setText("Отложенное название");
            click(activity,"Сохранить название");
            for(int i=0;i<100 && model.busy;i++){Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idle();}
            assertFalse(model.busy);
            assertNotNull(find(activity,"Название сохранено локально; синхронизация ожидает вебхук"));
            assertEquals("Отложенное название",model.selected.title);
            click(activity,"←  К списку");
            click(activity,"↻");
            for(int i=0;i<100 && model.busy;i++){Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idle();}
            assertFalse(model.busy);
            assertEquals(2,updates.get());
            assertEquals("Отложенное название",model.selected.title);
        }
    }
    @Test public void demoListOrdersTasksByDeadlineOldestFirst() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Посмотреть демоверсию");
            assertEquals(java.util.List.of("Обновить материалы встречи","Согласовать план проекта","Подготовить предложение клиенту"),cardTitles(activity));
            click(activity,"＋  Новая задача");
            EditText title=(EditText)findHint(activity.getWindow().getDecorView(),"Что нужно сделать?");
            title.setText("Задача без срока");
            click(activity,"Создать задачу");
            click(activity,"←  К списку");
            java.util.List<String> ordered=cardTitles(activity);
            assertEquals("Задача без срока",ordered.get(ordered.size()-1));
            assertEquals("Обновить материалы встречи",ordered.get(0));
        }
    }
    private static String deadlineOf(MainActivity.Model model,long id) {
        for(Task t:model.tasks) if(t.id==id) return t.deadline;
        throw new AssertionError("Задача #"+id+" не найдена в списке");
    }
    private static String titleOf(MainActivity.Model model,long id) {
        for(Task t:model.tasks) if(t.id==id) return t.title;
        throw new AssertionError("Задача #"+id+" не найдена в списке");
    }
    private static java.util.List<String> cardTitles(MainActivity activity) {
        java.util.List<String> out=new java.util.ArrayList<>(); collectCards(activity.getWindow().getDecorView(),out); return out;
    }
    private static void collectCards(View view,java.util.List<String> out) {
        Object cd=view.getContentDescription();
        if(cd!=null) { String s=cd.toString();
            if(s.contains("Ждёт выполнения")||s.contains("В работе")||s.contains("Завершена")) out.add(s.substring(0,s.indexOf(", "))); }
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)collectCards(g.getChildAt(i),out);}
    }
    private static void chooseDate(MainActivity activity,int year,int month,int day,int hour,int minute) {
        click(activity,"Выбрать дату и время");
        DatePickerDialog date=(DatePickerDialog)ShadowAlertDialog.getLatestAlertDialog();date.updateDate(year,month,day);
        date.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        TimePickerDialog time=(TimePickerDialog)ShadowAlertDialog.getLatestAlertDialog();
        TimePicker picker=(TimePicker)findType(time.getWindow().getDecorView(),TimePicker.class);
        assertNotNull(picker);picker.setHour(hour);picker.setMinute(minute);
        time.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static View findType(View view,Class<?> type) {
        if(type.isInstance(view))return view;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View v=findType(g.getChildAt(i),type);if(v!=null)return v;}}
        return null;
    }
    private static void click(MainActivity activity,String text) {View v=find(activity,text);assertNotNull(text,v);assertTrue(v.performClick());}
    private static View find(MainActivity activity,String text) {return findText(activity.getWindow().getDecorView(),text);}
    private static View findText(View view,String text) {
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup) {ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View v=findText(g.getChildAt(i),text);if(v!=null)return v;}}
        return null;
    }
    private static View findHint(View view,String hint) {
        if(view instanceof EditText && hint.contentEquals(((EditText)view).getHint()))return view;
        if(view instanceof ViewGroup) {ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View v=findHint(g.getChildAt(i),hint);if(v!=null)return v;}}
        return null;
    }
}
