package ru.local.bitrixtasks;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.widget.TimePicker;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
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
    @Test public void emptyTitleDoesNotCreateTask() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            click(activity,"Посмотреть демоверсию");click(activity,"＋  Новая задача");click(activity,"Создать задачу");
            assertNotNull(find(activity,"Укажите название задачи."));
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
            assertEquals(model.selected.deadline,model.tasks.get(0).deadline);
            click(activity,"Изменить крайний срок");click(activity,"Убрать срок");
            assertFalse(model.selected.deadline.isEmpty());
            click(activity,"Сохранить срок");assertEquals("",model.selected.deadline);
            assertEquals("",model.tasks.get(0).deadline);
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
            assertEquals(original,model.tasks.get(0).deadline);
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
