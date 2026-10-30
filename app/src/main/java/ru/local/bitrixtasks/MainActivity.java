package ru.local.bitrixtasks;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.JSONObject;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private int BG, INK, MUTED, GREEN, LINE, ACTION, GRAPHITE, TINT;
    private Model m;
    private LinearLayout root, body;
    private SecureStore store;
    private AppSettings settings;
    private LocalStore local;
    private String connectionInput="";
    private EditText connectionField;
    private EditText settingsWebhookField, settingsDaysField;
    private final Handler main = new Handler(Looper.getMainLooper());

    static final class Model {
        Webhook hook; BitrixApi api;
        List<Task> tasks=new ArrayList<>(), demoTasks=new ArrayList<>();
        boolean demo=false, busy=false, mine=true, ready=false;
        int tab=0, next=-1;
        String screen="connect", error="", query="", notice="";
        String draftTitle="", draftDescription="", draftResponsible="", draftDeadline="";
        String editedDeadline="";
        String editedTitle="";
        boolean draftPriority=false;
        Task selected;
        Runnable listener;
        final ExecutorService io=Executors.newSingleThreadExecutor();
        final Handler handler=new Handler(Looper.getMainLooper());
        interface Work<T> { T get() throws Exception; }
        interface Done<T> { void accept(T result) throws Exception; }
        <T> void run(Work<T> work, Done<T> done) {
            if (busy) return;
            busy=true; error=""; notice=""; changed();
            io.execute(() -> {
                T result=null; Exception failure=null;
                try { result=work.get(); } catch(Exception e) { failure=e; }
                T value=result; Exception problem=failure;
                handler.post(() -> {
                    busy=false;
                    if (problem!=null) error=problem instanceof BitrixApi.ApiException ? problem.getMessage() : "Не удалось выполнить операцию. Обновите список перед повторной попыткой.";
                    else try { done.accept(value); } catch(Exception e) { error="Не удалось сохранить настройки подключения."; }
                    changed();
                });
            });
        }
        void changed() { if(listener!=null) listener.run(); }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        BG=getColor(R.color.screen_background); INK=getColor(R.color.text_primary);
        MUTED=getColor(R.color.text_secondary); GREEN=getColor(R.color.brand_text);
        LINE=getColor(R.color.surface_line); ACTION=getColor(R.color.brand_action);
        GRAPHITE=getColor(R.color.brand_graphite); TINT=getColor(R.color.surface_tint);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store=new SecureStore(getApplicationContext());
        settings=new AppSettings(getApplicationContext());
        local=new LocalStore(getApplicationContext());
        Object retained=getLastNonConfigurationInstance();
        m=retained instanceof Model ? (Model)retained : new Model();
        if (retained==null) {
            try {
                String value=store.load();
                if(value!=null) { m.hook=Webhook.parse(value); m.api=new BitrixApi(m.hook); m.tasks=local.tasks(); m.ready=!m.tasks.isEmpty(); m.screen="tasks"; }
            } catch(Exception e) { m.error="Не удалось прочитать сохранённое подключение. Введите вебхук заново."; }
        }
        m.listener=this::render;
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        // Returning to the task list is a natural sync point after another app,
        // settings, or a temporary network outage has changed the environment.
        if(m!=null && m.api!=null && !m.demo && !m.busy && m.screen.equals("tasks")) load(false);
    }
    @Override public Object onRetainNonConfigurationInstance() { return m; }
    @Override protected void onDestroy() {
        m.listener=null;
        if(!isChangingConfigurations()) m.io.shutdown();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if(m.busy) { Toast.makeText(this,"Дождитесь завершения запроса",Toast.LENGTH_SHORT).show(); return; }
        if(m.screen.equals("deadline")) { closeDeadlineEditor(); }
        else if(m.screen.equals("title")) { m.screen="detail"; m.error=""; render(); }
        else if(m.screen.equals("settings")) { m.screen=m.hook==null?"connect":"tasks"; m.error=""; render(); }
        else if(m.screen.equals("create") || m.screen.equals("detail")) { m.screen="tasks"; m.error=""; render(); }
        else super.onBackPressed();
    }
    private void render() {
        if(isFinishing() || isDestroyed()) return;
        if(connectionField!=null) connectionInput=connectionField.getText().toString();
        if(settingsWebhookField!=null) connectionInput=settingsWebhookField.getText().toString();
        connectionField=null;
        settingsWebhookField=null; settingsDaysField=null;
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(0,0,0,dp(8)); setContentView(root);
        LinearLayout brand=row();
        brand.setBackgroundColor(GRAPHITE); brand.setPadding(dp(22),dp(14),dp(22),dp(14));
        ImageView logo=new ImageView(this); logo.setImageResource(R.drawable.grf_logo);
        logo.setContentDescription("ГРАФИТ"); logo.setScaleType(ImageView.ScaleType.FIT_START);
        brand.addView(logo,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView badge=text("Задачи\nBitrix24",12,0xffE0E0E0,false); badge.setGravity(Gravity.END);
        badge.setPadding(dp(12),0,0,0); brand.addView(badge);
        Button menu=new Button(this); menu.setText("☰"); menu.setTextSize(23); menu.setTextColor(Color.WHITE); menu.setAllCaps(false);
        menu.setContentDescription("Настройки"); menu.setBackgroundColor(Color.TRANSPARENT); menu.setPadding(0,0,0,0);
        menu.setMinWidth(0); menu.setMinimumWidth(0); menu.setOnClickListener(v->{ if(!m.busy){m.screen="settings";m.error="";m.notice="";render();} });
        brand.addView(menu,new LinearLayout.LayoutParams(dp(52),dp(52)));
        root.addView(brand);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        body=column(); body.setPadding(dp(22),dp(24),dp(22),dp(20)); scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(m.screen.equals("connect")) connectScreen();
        else if(m.screen.equals("create")) createScreen();
        else if(m.screen.equals("detail")) detailScreen();
        else if(m.screen.equals("deadline")) deadlineScreen();
        else if(m.screen.equals("title")) titleScreen();
        else if(m.screen.equals("settings")) settingsScreen();
        else taskScreen();
        if(m.busy) { ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(4))); }
    }
    private void messages() {
        if(!m.error.isEmpty()) { TextView e=text(m.error,14,0xff9D352D,false); e.setPadding(dp(14),dp(12),dp(14),dp(12)); e.setBackground(round(0xffFBE8E1,12)); add(e,12); }
        if(!m.notice.isEmpty()) { TextView e=text(m.notice,14,GREEN,false); e.setPadding(dp(14),dp(12),dp(14),dp(12)); e.setBackground(round(TINT,12)); add(e,12); }
    }
    private void connectScreen() {
        add(text("РАБОЧИЙ ДЕНЬ ПОД КОНТРОЛЕМ",11,GREEN,true),18);
        add(text("Меньше суеты.\nБольше сделанного.",34,INK,true),12);
        add(text("Ваши задачи Bitrix24 — рядом. Подключите портал и сосредоточьтесь на главном.",16,MUTED,false),24);
        messages();
        add(text("Подключение к порталу",20,INK,true),8);
        add(text("Входящий вебхук",13,MUTED,true),6);
        connectionField=input("https://company.bitrix24.ru/rest/1/ключ/",connectionInput,false);
        connectionField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        connectionField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        if(Build.VERSION.SDK_INT>=26) connectionField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        connectionField.setSaveEnabled(false);
        add(connectionField,12);
        add(button(m.busy ? "Проверяем подключение…" : "Подключить портал  →",true,()->connect(connectionField.getText().toString())),10);
        add(button("Посмотреть демоверсию",false,this::startDemo),22);
        LinearLayout note=column(); note.setPadding(dp(18),dp(18),dp(18),dp(18)); note.setBackground(round(TINT,18));
        note.addView(text("Как получить вебхук",15,INK,true));
        TextView steps=text("В Bitrix24 откройте раздел разработчика → Другое → Входящий вебхук. Добавьте право «Задачи (task)» и скопируйте адрес для вызова REST API.",14,MUTED,false); steps.setPadding(0,dp(8),0,dp(12)); note.addView(steps);
        note.addView(text("Все действия выполняются от имени владельца вебхука. Ключ хранится зашифрованным на этом устройстве.",12,MUTED,false)); add(note,10);
        add(text("Независимый клиент · Android 10+",12,MUTED,false),0);
    }
    private void connect(String input) {
        final Webhook hook;
        try { hook=Webhook.parse(input); } catch(IllegalArgumentException e) { m.error=e.getMessage(); render(); return; }
        BitrixApi api=new BitrixApi(hook);
        m.run(()->api.list(0,"",0,true,hook.userId),page->{
            if(m.hook!=null && !m.hook.baseUrl.equals(hook.baseUrl)) local.clearAll();
            store.save(hook.baseUrl); m.hook=hook; m.api=api; m.demo=false; m.mine=true; m.tab=0; m.query="";
            m.tasks=page.tasks; m.next=page.next; m.ready=true; m.screen="tasks"; connectionInput=""; connectionField=null;
        });
    }
    private void taskScreen() {
        add(text(m.demo ? "ДЕМОПОРТАЛ  /  ПРИМЕР ДАННЫХ" : m.hook.host,12,GREEN,true),8);
        add(text("Ваши задачи",32,INK,true),6);
        add(text(m.demo ? "Попробуйте создать и завершить задачу." : "Один ясный список для рабочего дня.",14,MUTED,false),20);
        messages();
        LinearLayout actions=row(); actions.addView(button("＋  Новая задача",true,()->{ clearDraft(); m.screen="create"; m.error=""; m.notice=""; render(); }),new LinearLayout.LayoutParams(0,dp(52),1));
        Button refresh=button("↻",false,()->load(false)); refresh.setContentDescription("Обновить список задач"); LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(58),dp(52)); rp.leftMargin=dp(8); actions.addView(refresh,rp); add(actions,16);
        LinearLayout tabs=row();
        String[] labels={"Активные","Готово","Все"};
        for(int i=0;i<labels.length;i++) { final int tab=i; Button b=button(labels[i],m.tab==i,()->{m.tab=tab; load(false);}); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1); if(i>0)p.leftMargin=dp(5); tabs.addView(b,p); }
        add(tabs,10);
        CheckBox mine=new CheckBox(this); mine.setText("Только я — исполнитель"); mine.setTextColor(INK); mine.setChecked(m.mine); mine.setEnabled(!m.busy); mine.setMinHeight(dp(48)); mine.setOnCheckedChangeListener((b,value)->{ m.mine=value; load(false); }); add(mine,8);
        LinearLayout searchRow=row(); EditText search=input("Поиск по названию",m.query,false); search.setImeOptions(EditorInfo.IME_ACTION_SEARCH); searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(50),1));
        Runnable doSearch=()->{ m.query=search.getText().toString().trim(); load(false); };
        search.setOnEditorActionListener((v,id,event)->{ if(id==EditorInfo.IME_ACTION_SEARCH && !m.busy) { doSearch.run(); return true; } return false; });
        Button find=button("Найти",false,doSearch); LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(85),dp(50)); fp.leftMargin=dp(6); searchRow.addView(find,fp); add(searchRow,16);
        if(!m.ready && !m.busy) add(button("Загрузить задачи",false,()->load(false)),12);
        if(m.tasks.isEmpty() && m.ready && !m.busy) {
            add(text("✓",44,GREEN,true),8);
            add(text("Здесь пока нет задач",21,INK,true),6);
            add(text(m.query.isEmpty() ? "Создайте задачу или измените фильтр." : "Попробуйте другое название или измените фильтр.",14,MUTED,false),24);
        }
        if(!m.tasks.isEmpty()) add(text("ЗАГРУЖЕНО: " + m.tasks.size() + (m.next>=0 ? "  ·  ЕСТЬ ЕЩЁ" : ""),11,MUTED,true),12);
        for(Task task:m.tasks) taskCard(task);
        if(m.next>=0) add(button("Загрузить ещё",false,()->load(true)),16);
        add(button(m.demo ? "Выйти из деморежима" : "Отключить портал",false,this::disconnect),6);
    }
    private void taskCard(Task t) {
        LinearLayout card=column(); card.setPadding(dp(16),dp(16),dp(16),dp(16)); card.setBackground(round(Color.WHITE,18)); card.setElevation(dp(1));
        TextView status=text((t.highPriority ? "↑  ВАЖНО  ·  " : "") + t.statusLabel().toUpperCase(new Locale("ru")),11,t.status==5?GREEN:MUTED,true); card.addView(status);
        TextView title=text(t.title,19,INK,true); title.setPadding(0,dp(9),0,dp(12)); card.addView(title);
        card.addView(text((t.overdue()?"Просрочена · ":"") + t.dateLabel(),13,t.overdue()?0xffAC4234:MUTED,false));
        TextView person=text(t.responsibleName+"   ·   #"+t.id,12,MUTED,false); person.setPadding(0,dp(8),0,0); card.addView(person);
        card.setFocusable(true); card.setContentDescription(t.title+", "+t.statusLabel()+", "+t.dateLabel());
        card.setOnClickListener(v->{ if(m.busy)return; m.error="";m.notice=""; if(m.demo || m.api==null){m.selected=t;m.screen="detail";render();} else { m.run(()->m.api.getTask(t.id),full->{local.upsert(full);m.selected=full;m.screen="detail";}); } }); add(card,12);
    }
    private void load(boolean more) {
        if(m.busy)return;
        final int start=more?m.next:0, tab=m.tab; final String query=m.query; final boolean mine=m.mine;
        if(!more && !m.demo) { m.tasks=local.tasks(); m.ready=!m.tasks.isEmpty(); m.next=-1; render(); }
        else if(!more) { m.tasks=new ArrayList<>(); m.ready=false; m.next=-1; }
        if(m.demo) {
            List<Task> visible=new ArrayList<>();
            for(Task t:m.demoTasks) if((tab==2 || (tab==1 ? t.status==5 : t.status!=5))
                    && (!mine || t.responsibleId==1) && t.title.toLowerCase(new Locale("ru")).contains(query.toLowerCase(new Locale("ru")))) visible.add(t);
            m.tasks=visible; m.ready=true; m.next=-1; m.error=""; render(); return;
        }
        final BitrixApi api=m.api; final long user=m.hook.userId;
        m.run(()->{ syncPending(api); return api.list(start,query,tab,mine,user); },page->{
            if(more) { Set<Long> ids=new HashSet<>(); for(Task t:m.tasks)ids.add(t.id); for(Task t:page.tasks)if(ids.add(t.id))m.tasks.add(t); }
            else m.tasks=page.tasks;
            m.next=page.next; m.ready=true; for(Task t:page.tasks)local.upsert(t); m.notice="";
        });
    }
    private void syncPending(BitrixApi api) throws Exception {
        Map<Long,Long> remap=new HashMap<>();
        for(LocalStore.Pending p:local.pending()) {
            long target=remap.containsKey(p.taskId)?remap.get(p.taskId):p.taskId;
            if(p.type.equals("ADD")) { Task remote=api.add(p.payload.optString("title"),p.payload.optString("description"),p.payload.optLong("responsibleId"),p.payload.optString("deadline"),p.payload.optBoolean("priority")); local.remove(p.taskId);local.upsert(remote);remap.put(p.taskId,remote.id); }
            else if(p.type.equals("COMPLETE")) local.upsert(api.complete(target));
            else if(p.type.equals("DEADLINE")) local.upsert(api.updateDeadline(target,p.payload.optString("deadline")));
            else if(p.type.equals("TITLE")) local.upsert(api.updateTitle(target,p.payload.optString("title")));
            local.removePending(p.id);
        }
    }
    private void detailScreen() {
        Task t=m.selected;
        add(button("←  К списку",false,()->{m.screen="tasks";m.error="";render();}),16);
        add(text("ЗАДАЧА #"+t.id,12,GREEN,true),10); add(text(t.title,29,INK,true),14);
        messages();
        add(text(t.statusLabel()+(t.highPriority?"  ·  Важная":""),16,GREEN,true),18);
        add(text("ИСПОЛНИТЕЛЬ",11,MUTED,true),5); add(text(t.responsibleName+" (#"+t.responsibleId+")",16,INK,false),18);
        add(button("Изменить название",false,()->{m.editedTitle=t.title;m.screen="title";m.error="";m.notice="";render();}),10);
        add(text("КРАЙНИЙ СРОК",11,MUTED,true),5); add(text(t.dateLabel()+(t.overdue()?" · Просрочена":""),16,t.overdue()?0xffAC4234:INK,false),22);
        add(button("Изменить крайний срок",false,()->{
            m.editedDeadline=t.deadline; m.screen="deadline"; m.error=""; m.notice=""; render();
        }),22);
        add(text("ОПИСАНИЕ",11,MUTED,true),8); TextView desc=text(t.description.isEmpty()?"Описание не добавлено":t.description,16,INK,false); desc.setTextIsSelectable(true); add(desc,26);
        if(!t.checklist.isEmpty()) {
            add(text("ЧЕК-ЛИСТ",11,MUTED,true),8);
            LinearLayout list=column(); list.setPadding(dp(14),dp(12),dp(14),dp(10)); list.setBackground(round(TINT,14));
            for(Task.ChecklistItem item:t.checklist) { TextView itemView=text((item.complete?"✓  ":"○  ")+item.title,14,item.complete?GREEN:INK,false); itemView.setPadding(0,0,0,dp(8)); list.addView(itemView); }
            add(list,18);
        }
        if(t.canComplete) add(button("✓  Завершить задачу",true,()->new AlertDialog.Builder(this).setTitle("Завершить задачу?").setMessage(t.title)
                .setNegativeButton("Отмена",null).setPositiveButton("Завершить",(d,w)->complete(t)).show()),10);
        if(!m.demo) add(button("Открыть в Bitrix24",false,()->openPortal(t)),10);
        if(!t.canComplete && t.status!=5) add(text("Завершение недоступно для текущего пользователя или статуса.",13,MUTED,false),0);
    }
    private void titleScreen() {
        add(button("←  К задаче",false,()->{m.screen="detail";m.error="";render();}),16);
        add(text("Название задачи",26,INK,true),8); messages();
        EditText title=input("Название",m.editedTitle,false); title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(255)}); watch(title,v->m.editedTitle=v); add(title,16);
        Button save=button(m.busy?"Сохраняем…":"Сохранить название",true,this::saveTitle);
        if(m.editedTitle.trim().isEmpty()||m.editedTitle.trim().equals(m.selected.title)){save.setEnabled(false);save.setAlpha(.5f);} add(save,10);
        add(button("Отмена",false,()->{m.screen="detail";m.error="";render();}),8);
    }
    private void saveTitle() {
        String title=m.editedTitle.trim(); if(title.isEmpty()){m.error="Укажите название задачи.";render();return;} if(title.equals(m.selected.title)||m.busy)return;
        final Task old=m.selected;
        if(m.demo) { try { Task changed=new Task(old.toJson().put("title",title)); replace(changed);m.selected=changed;m.screen="detail";m.notice="Название обновлено в деморежиме";render(); } catch(Exception e){m.error="Не удалось изменить название.";render();} return; }
        m.run(()->{try{return m.api.updateTitle(old.id,title);}catch(Exception offline){if(offline instanceof BitrixApi.ApiException)throw offline;Task changed=new Task(old.toJson().put("title",title));local.upsert(changed);local.enqueue("TITLE",old.id,new JSONObject().put("title",title));return changed;}},task->{replace(task);m.selected=task;m.screen="detail";m.notice=task.id<0?"Название сохранено локально; синхронизация ожидает вебхук":"Название обновлено в Bitrix24";});
    }
    private void complete(Task task) {
        if(m.demo) {
            try { Task done=demoTask(task.id,task.title,task.description,5,task.responsibleId,task.deadline,task.highPriority); replace(done); m.selected=done; m.notice="Задача завершена в деморежиме"; render(); } catch(Exception ignored) {} return;
        }
        m.run(()->{ try { return m.api.complete(task.id); } catch(Exception offline) { if(offline instanceof BitrixApi.ApiException) throw offline; Task done=demoTask(task.id,task.title,task.description,5,task.responsibleId,task.deadline,task.highPriority); local.upsert(done); local.enqueue("COMPLETE",task.id,new JSONObject()); return done; } },done->{ replace(done); m.selected=done; m.notice=done.id<0?"Задача завершена локально; синхронизация ожидает вебхук":(done.status==5?"Задача завершена":"Новый статус: "+done.statusLabel()); });
    }
    private void closeDeadlineEditor() {
        m.screen="detail"; m.editedDeadline=""; m.error=""; render();
    }
    private void deadlineScreen() {
        add(button("←  К задаче",false,this::closeDeadlineEditor),16);
        add(text("Крайний срок",30,INK,true),8);
        add(text(m.selected.title,17,INK,false),20);
        messages();
        fieldLabel("Новый срок");
        add(text(deadlineLabel(m.editedDeadline),22,INK,true),12);
        add(button("Выбрать дату и время",false,()->pickDate(m.editedDeadline,value->{m.editedDeadline=value;render();})),10);
        if(!m.editedDeadline.isEmpty()) add(button("Убрать срок",false,()->{m.editedDeadline="";render();}),10);
        add(text("Часовой пояс: "+ZoneId.systemDefault().getId()+". Изменения применятся после сохранения.",13,MUTED,false),22);
        Button save=button(m.busy?"Сохраняем…":"Сохранить срок",true,this::saveDeadline);
        if(m.editedDeadline.equals(m.selected.deadline)) {save.setEnabled(false);save.setAlpha(.5f);}
        add(save,10);
        add(button("Отмена",false,this::closeDeadlineEditor),8);
    }
    private void settingsScreen() {
        add(button("←  Назад",false,()->{m.screen=m.hook==null?"connect":"tasks";m.error="";render();}),16);
        add(text("Настройки",30,INK,true),8);
        add(text("Подключение и срок по умолчанию для новых задач",14,MUTED,false),20);
        messages();
        fieldLabel("Адрес входящего вебхука");
        String current=connectionInput;
        if(current.isEmpty() && m.hook!=null) current=m.hook.baseUrl;
        settingsWebhookField=input("https://company.bitrix24.ru/rest/1/ключ/",current,false);
        settingsWebhookField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        settingsWebhookField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        if(Build.VERSION.SDK_INT>=26) settingsWebhookField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); add(settingsWebhookField,8);
        add(text("Адрес хранится зашифрованным на этом устройстве. После сохранения приложение проверит доступ к задачам.",12,MUTED,false),18);
        fieldLabel("Срок по умолчанию");
        AppSettings.DeadlineMode mode=settings.mode();
        LinearLayout choices=row();
        choices.addView(button("Без срока",mode==AppSettings.DeadlineMode.NONE,()->{settings.save(AppSettings.DeadlineMode.NONE,settings.days(),settings.hour(),settings.minute());render();}),new LinearLayout.LayoutParams(0,dp(48),1));
        Button tomorrow=button("Завтра",mode==AppSettings.DeadlineMode.TOMORROW,()->{settings.save(AppSettings.DeadlineMode.TOMORROW,settings.days(),settings.hour(),settings.minute());render();});
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(48),1);cp.leftMargin=dp(5);choices.addView(tomorrow,cp); add(choices,8);
        LinearLayout daysRow=row();
        settingsDaysField=input("3",Integer.toString(settings.days()),false); settingsDaysField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        daysRow.addView(settingsDaysField,new LinearLayout.LayoutParams(dp(92),dp(50)));
        Button days=button("дня / дней",mode==AppSettings.DeadlineMode.DAYS,()->{int d=parseDays();settings.save(AppSettings.DeadlineMode.DAYS,d,settings.hour(),settings.minute());render();});
        LinearLayout.LayoutParams dparams=new LinearLayout.LayoutParams(0,dp(50),1);dparams.leftMargin=dp(7);daysRow.addView(days,dparams); add(daysRow,8);
        add(text("Пример: «Завтра» или «через 3 дня» в выбранное время.",12,MUTED,false),14);
        add(button("Время: "+String.format(Locale.ROOT,"%02d:%02d",settings.hour(),settings.minute()),false,()->new TimePickerDialog(this,(v,h,min)->{settings.save(mode,parseDays(),h,min);render();},settings.hour(),settings.minute(),true).show()),16);
        add(button(m.busy?"Сохраняем…":"Сохранить настройки",true,this::saveSettings),10);
        add(button("Отмена",false,()->{m.screen=m.hook==null?"connect":"tasks";connectionInput="";m.error="";render();}),8);
    }
    private int parseDays() {
        try { return Math.max(1,Math.min(365,Integer.parseInt(settingsDaysField==null?Integer.toString(settings.days()):settingsDaysField.getText().toString().trim()))); }
        catch(Exception ignored) { return settings.days(); }
    }
    private void saveSettings() {
        if(m.busy)return;
        String address=settingsWebhookField==null?connectionInput:settingsWebhookField.getText().toString().trim();
        AppSettings.DeadlineMode mode=settings.mode();
        settings.save(mode,parseDays(),settings.hour(),settings.minute());
        if(m.demo && address.isEmpty()) { m.screen="tasks";m.notice="Настройки сохранены";render();return; }
        final Webhook hook;
        try { hook=Webhook.parse(address); } catch(IllegalArgumentException e) {m.error=e.getMessage();render();return;}
        if(m.demo || m.hook==null || !hook.baseUrl.equals(m.hook.baseUrl)) {
            BitrixApi api=new BitrixApi(hook);
            m.run(()->api.list(0,"",0,true,hook.userId),page->{
                store.save(hook.baseUrl);m.hook=hook;m.api=api;m.demo=false;m.mine=true;m.tab=0;m.query="";m.tasks=page.tasks;m.next=page.next;m.ready=true;m.screen="tasks";connectionInput="";m.notice="Настройки сохранены";
            });
        } else { m.screen="tasks";connectionInput="";m.notice="Настройки сохранены";render(); }
    }
    private void saveDeadline() {
        if(m.busy || m.editedDeadline.equals(m.selected.deadline))return;
        final Task task=m.selected;
        final String deadline=m.editedDeadline;
        if(m.demo) {
            try { deadlineSaved(demoTask(task.id,task.title,task.description,task.status,task.responsibleId,deadline,task.highPriority)); }
            catch(Exception e) {m.error="Не удалось изменить срок.";}
            render(); return;
        }
        final BitrixApi api=m.api;
        m.run(()->{ try { return api.updateDeadline(task.id,deadline); } catch(Exception offline) { if(offline instanceof BitrixApi.ApiException) throw offline; Task changed=demoTask(task.id,task.title,task.description,task.status,task.responsibleId,deadline,task.highPriority); local.upsert(changed); local.enqueue("DEADLINE",task.id,new JSONObject().put("deadline",deadline)); return changed; } },this::deadlineSaved);
    }
    private void deadlineSaved(Task task) {
        if(!m.demo) local.upsert(task);
        replace(task); m.selected=task; m.editedDeadline=""; m.screen="detail";
        m.notice=(task.deadline.isEmpty()?"Крайний срок снят":"Крайний срок обновлён")+(m.demo?" в деморежиме":(task.id<0?" локально; синхронизация ожидает вебхук":" в Bitrix24"));
    }
    private void replace(Task task) {
        if(!m.demo) local.upsert(task);
        if(m.demo) for(int i=0;i<m.demoTasks.size();i++) if(m.demoTasks.get(i).id==task.id) {m.demoTasks.set(i,task);break;}
        for(int i=0;i<m.tasks.size();i++) if(m.tasks.get(i).id==task.id) {m.tasks.set(i,task);break;}
        boolean visible=(m.tab==2 || (m.tab==1?task.status==5:task.status!=5))
                && (!m.mine || task.responsibleId==(m.demo?1:m.hook.userId))
                && task.title.toLowerCase(Locale.ROOT).contains(m.query.toLowerCase(Locale.ROOT));
        if(!visible)m.tasks.removeIf(t->t.id==task.id);
    }
    private void openPortal(Task t) {
        String address="https://"+m.hook.host+"/company/personal/user/"+t.responsibleId+"/tasks/task/view/"+t.id+"/";
        try { startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(address))); }
        catch(ActivityNotFoundException e) {m.error="На устройстве не найден браузер.";render();}
    }
    private void clearDraft() {
        m.draftTitle="";m.draftDescription="";m.draftResponsible=Long.toString(m.demo?1:m.hook.userId);m.draftPriority=false;
        m.draftDeadline=defaultDeadline();
    }
    private String defaultDeadline() {
        AppSettings.DeadlineMode mode=settings.mode(); if(mode==AppSettings.DeadlineMode.NONE)return "";
        ZonedDateTime value=ZonedDateTime.now().plusDays(mode==AppSettings.DeadlineMode.TOMORROW?1:settings.days())
                .withHour(settings.hour()).withMinute(settings.minute()).withSecond(0).withNano(0);
        return value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
    private void createScreen() {
        add(button("←  К списку",false,()->{m.screen="tasks";m.error="";render();}),16);
        add(text("Новая задача",24,INK,true),12); messages();
        fieldLabel("Название *"); EditText title=input("Что нужно сделать?",m.draftTitle,false); title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(255)}); watch(title,v->m.draftTitle=v); add(title,16);
        fieldLabel("Описание"); EditText desc=input("Детали и ожидаемый результат",m.draftDescription,false); watch(desc,v->m.draftDescription=v); add(desc,16);
        fieldLabel("ID исполнителя *"); EditText user=input("Например, 1",m.draftResponsible,false); user.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); watch(user,v->m.draftResponsible=v); add(user,6);
        add(text("По умолчанию — владелец вебхука. ID коллеги можно найти в адресе его профиля в Bitrix24.",12,MUTED,false),16);
        fieldLabel("Крайний срок");
        String deadline=m.draftDeadline.isEmpty()?"Выбрать дату и время":deadlineLabel(m.draftDeadline);
        add(button(deadline,false,this::pickDate),6);
        if(!m.draftDeadline.isEmpty()) add(button("Убрать срок",false,()->{m.draftDeadline="";render();}),6);
        CheckBox priority=new CheckBox(this); priority.setText("Важная задача");priority.setTextColor(INK);priority.setChecked(m.draftPriority);priority.setEnabled(!m.busy);priority.setMinHeight(dp(48));priority.setOnCheckedChangeListener((b,value)->m.draftPriority=value);add(priority,20);
        add(button(m.busy?"Создаём…":"Создать задачу",true,this::createTask),8);
    }
    private void pickDate() {
        pickDate(m.draftDeadline,value->{m.draftDeadline=value;render();});
    }
    private String deadlineLabel(String deadline) {
        if(deadline.isEmpty())return "Без срока";
        try {return OffsetDateTime.parse(deadline).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));}
        catch(java.time.format.DateTimeParseException e) {return deadline;}
    }
    private void pickDate(String value,Change selected) {
        ZonedDateTime initial=ZonedDateTime.now();
        try {if(!value.isEmpty())initial=OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault());}
        catch(java.time.format.DateTimeParseException ignored) {}
        final ZonedDateTime now=initial;
        new DatePickerDialog(this,(v,y,month,day)->new TimePickerDialog(this,(time,h,min)->{
            selected.set(ZonedDateTime.of(y,month+1,day,h,min,0,0,ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        },now.getHour(),now.getMinute(),true).show(),now.getYear(),now.getMonthValue()-1,now.getDayOfMonth()).show();
    }
    private void createTask() {
        String title=m.draftTitle.trim(), description=m.draftDescription.trim(), deadline=m.draftDeadline;
        final long responsible;
        if(title.isEmpty()) {m.error="Укажите название задачи.";render();return;}
        try {responsible=Long.parseLong(m.draftResponsible.trim()); if(responsible<=0)throw new NumberFormatException();}
        catch(Exception e) {m.error="ID исполнителя должен быть положительным числом.";render();return;}
        final boolean priority=m.draftPriority;
        if(m.demo) {
            try {Task task=demoTask(System.currentTimeMillis(),title,description,2,responsible,deadline,priority);m.demoTasks.add(0,task);created(task);}catch(Exception ignored){} render(); return;
        }
        m.run(()->{ try { return m.api.add(title,description,responsible,deadline,priority); } catch(Exception offline) { if(offline instanceof BitrixApi.ApiException) throw offline; long id=local.localId(); Task task=demoTask(id,title,description,2,responsible,deadline,priority); local.upsert(task); local.enqueue("ADD",id,new JSONObject().put("title",title).put("description",description).put("responsibleId",responsible).put("deadline",deadline).put("priority",priority)); return task; } },this::created);
    }
    private void created(Task task) {
        if(!m.demo) local.upsert(task);
        m.selected=task;m.screen="detail";m.notice=m.demo?"Задача создана в деморежиме":(task.id<0?"Задача сохранена локально; синхронизация ожидает вебхук":"Задача создана в Bitrix24");
        if((!m.mine || task.responsibleId==(m.demo?1:m.hook.userId)) && m.tab!=1 && task.title.toLowerCase(Locale.ROOT).contains(m.query.toLowerCase(Locale.ROOT)))m.tasks.add(0,task);
        clearDraft();
    }
    private void disconnect() {
        new AlertDialog.Builder(this).setTitle(m.demo?"Выйти из деморежима?":"Отключить портал?")
                .setMessage(m.demo?"Демонстрационные изменения будут удалены.":"Сохранённый вебхук будет удалён с устройства. Задачи в Bitrix24 сохранятся.")
                .setNegativeButton("Отмена",null).setPositiveButton("Выйти",(d,w)->{
                    if(!m.demo && !store.clear()) {m.error="Не удалось удалить сохранённое подключение. Повторите попытку.";render();return;}
                    local.clearAll();
                    m.hook=null;m.api=null;m.demo=false;m.tasks.clear();m.demoTasks.clear();m.selected=null;m.screen="connect";m.error="";m.notice="";m.ready=false;m.query="";connectionInput="";render();
                }).show();
    }
    private void startDemo() {
        m.demo=true;m.mine=true;m.tab=0;m.query="";m.screen="tasks";m.error="";m.notice="";m.demoTasks.clear();
        try {
            m.demoTasks.add(demoTask(104,"Согласовать план проекта","Обсудить этапы с командой и зафиксировать сроки запуска.",3,1,ZonedDateTime.now().plusHours(3).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),true));
            m.demoTasks.add(demoTask(103,"Подготовить предложение клиенту","Собрать требования, оценить объём работ и подготовить предложение.",2,1,ZonedDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),false));
            m.demoTasks.add(demoTask(102,"Обновить материалы встречи","Добавить итоги обсуждения и следующие шаги.",2,1,ZonedDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),false));
            m.demoTasks.add(demoTask(101,"Проверить макеты","Проверить тексты и состояния экранов.",2,2,"",false));
            m.demoTasks.add(demoTask(100,"Отправить итоги недели","Отправлено команде.",5,1,"",false));
        }catch(Exception ignored){} load(false);
    }
    private Task demoTask(long id,String title,String desc,int status,long user,String deadline,boolean priority)throws Exception {
        return new Task(new JSONObject().put("id",id).put("title",title).put("description",desc).put("status",status).put("responsibleId",user).put("responsible",new JSONObject().put("name",user==1?"Вы":"Коллега #"+user)).put("deadline",deadline).put("priority",priority?2:1));
    }
    private int dp(float n) {return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(2),1);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void add(View v,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);body.addView(v,p);}
    private void fieldLabel(String label){add(text(label,13,INK,true),7);}
    private Button button(String label,boolean primary,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(primary?Color.WHITE:GREEN);b.setBackground(round(primary?ACTION:TINT,13));b.setMinHeight(dp(50));b.setMinimumHeight(dp(50));b.setPadding(dp(10),dp(8),dp(10),dp(8));b.setEnabled(!m.busy);b.setAlpha(m.busy?.5f:1f);b.setOnClickListener(v->action.run());return b;}
    private EditText input(String hint,String value,boolean multiline){EditText e=new EditText(this);e.setTextSize(16);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setHint(hint);e.setText(value);e.setPadding(dp(14),dp(13),dp(14),dp(13));GradientDrawable d=round(Color.WHITE,12);d.setStroke(dp(1),LINE);e.setBackground(d);e.setSingleLine(!multiline);e.setGravity(Gravity.TOP|Gravity.START);e.setEnabled(!m.busy);e.setMinHeight(dp(50));e.setSaveEnabled(false);return e;}
    interface Change{void set(String v);}
    private void watch(EditText e,Change c){e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int co,int af){}public void onTextChanged(CharSequence s,int st,int before,int count){c.set(s.toString());}public void afterTextChanged(Editable v){}});}
}
