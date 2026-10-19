package br.com.socorro;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.hardware.*;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Outbox db;
    private SensorManager sensorManager;
    private SensorReadings readings;
    private LocationManager locationManager;
    private Location previewLocation;
    private LinearLayout root, content, alertBox, historyRows, panicDock;
    private BackSupport backSupport;
    private ScrollView scroll;
    private TextView alertClock, alertTitle, alertDetail;
    private ProgressBar alertProgress;
    private final Map<String,TextView> labels = new HashMap<>();
    private Button monitorButton, mapButton, whatsAppButton;
    private TraceView trace;
    private SignalView signal;
    private EditText nameInput;
    private Switch smsSwitch;
    private boolean dark, rendering, resumed;
    private long lastSlow, lastGraph;
    private boolean lightThemeInitialized;
    private int tab, historyFilter, battery = -1;
    private long historyRevision = -1;
    private String pendingAction;
    private Dialog openedDialog;
    private int bg, card, ink, muted, line, accent, soft, danger;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("socorro", MODE_PRIVATE); db = new Outbox(this);
        if (!MonitorService.running) db.finishInterrupted();
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        readings = new SensorReadings(sensorManager); locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        if (state != null) { tab = state.getInt("tab"); historyFilter = state.getInt("filter"); }
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= 33) backSupport = new BackSupport(this);
        build();
    }
    private void palette() {
        bg = color(dark ? "#000000" : "#F5F4EF"); card = color(dark ? "#151515" : "#FFFEFA");
        ink = color(dark ? "#F5F5F5" : "#20382D"); muted = color(dark ? "#B3B3B3" : "#6E7A70");
        line = color(dark ? "#333333" : "#DDDFD5"); accent = color(dark ? "#E0E0E0" : "#28594D");
        soft = color(dark ? "#252525" : "#E8EDE3"); danger = color(dark ? "#E58B77" : "#A33F31");
    }
    private void build() {
        rendering = true; palette(); labels.clear(); nameInput = null; smsSwitch = null;
        trace = null; signal = null; monitorButton = null; mapButton = null; historyRows = null;
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        root = column(); root.setBackgroundColor(bg); root.setTag("root");
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(b.left,b.top,b.right,b.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        setContentView(root);
        if(Build.VERSION.SDK_INT>=30&&getWindow().getInsetsController()!=null){
            int appearance=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(dark?0:appearance,appearance);
        }
        LinearLayout brand = row(); brand.setPadding(dp(22),dp(12),dp(22),dp(10));
        brand.addView(new Glyph(this,"shield",accent),new LinearLayout.LayoutParams(dp(26),dp(28)));
        TextView logo = text("socorro",23,ink,true); logo.setLetterSpacing(-0.04f); LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(0,-2,1); logoLp.leftMargin=dp(8); brand.addView(logo,logoLp);
        TextView live = text(MonitorService.running ? "EM TRAJETO" : "PAUSADO",10,muted,true); live.setLetterSpacing(.08f); brand.addView(live); labels.put("live",live);
        root.addView(brand); makeAlert();
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = column(); content.setPadding(dp(22),dp(12),dp(22),dp(24)); scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        switch(tab) { case 1: sensorsScreen(); break; case 2: historyScreen(); break; case 3: settingsScreen(); break; default: homeScreen(); }
        panicDock=column();panicDock.setPadding(dp(22),dp(6),dp(22),dp(9));
        Button sos=button("Preciso de ajuda · SOS",color(dark?"#C55B48":"#AD4738"),Color.WHITE);sos.setTag("sos");sos.setOnClickListener(v->start("SOS"));panicDock.addView(sos);
        whatsAppButton=outlineButton("WhatsApp · mensagens pendentes");whatsAppButton.setTag("whatsappPending");whatsAppButton.setOnClickListener(v->showWhatsAppAlerts());panicDock.addView(whatsAppButton);
        root.addView(panicDock);
        View border = new View(this); border.setBackgroundColor(line); root.addView(border,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout nav = row(); nav.setPadding(dp(10),dp(7),dp(10),dp(5)); nav.setBackgroundColor(card);
        String[] names={"Início","Sensores","Histórico","Ajustes"}, icons={"home","wave","history","settings"};
        for(int i=0;i<4;i++) {
            final int target=i; LinearLayout item=column(); item.setGravity(Gravity.CENTER); item.setMinimumHeight(dp(64)); item.setPadding(dp(4),dp(6),dp(4),dp(6));
            if(tab==i)item.setBackground(round(soft,14));
            LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(23),dp(23));iconLp.gravity=Gravity.CENTER_HORIZONTAL;
            item.addView(new Glyph(this,icons[i],tab==i?accent:muted),iconLp);
            TextView label=text(names[i],11,tab==i?accent:muted,tab==i);label.setGravity(Gravity.CENTER);label.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);label.setSingleLine(true);label.setPadding(0,dp(4),0,0);
            item.addView(label,new LinearLayout.LayoutParams(-1,-2));
            item.setTag("nav"+i); item.setContentDescription(names[i]); item.setFocusable(true); item.setOnClickListener(v->selectTab(target));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(2),0,dp(2),0);nav.addView(item,lp);
        }
        root.addView(nav); rendering=false; root.requestApplyInsets(); historyRevision=-1; lastSlow=0;
        if (backSupport != null) backSupport.update(tab != 0);
        update();
    }
    void selectTab(int target) { if(nameInput!=null&&!nameInput.getText().toString().trim().isEmpty())saveNameDraft();tab=target; build(); }
    private void heading(String eyebrow,String title,String subtitle) {
        TextView eye=text(eyebrow,10,muted,true);eye.setLetterSpacing(.12f);content.addView(eye);
        TextView h=text(title,26,ink,true);h.setPadding(0,dp(8),0,dp(7));h.setLineSpacing(0,.98f);content.addView(h);
        if(subtitle!=null){TextView sub=text(subtitle,14,muted,false);sub.setLineSpacing(dp(3),1);content.addView(sub);}
        space(content,16);
    }
    private boolean saveNameDraft(){
        if(nameInput==null)return !prefs.getString("name","").trim().isEmpty();
        String name=nameInput.getText().toString().trim();if(name.isEmpty()){nameInput.setError("Informe seu nome");return false;}
        prefs.edit().putString("name",name).apply();return true;
    }
    private void homeScreen() {
        String savedName=prefs.getString("name","").trim();String firstName=savedName.isEmpty()?"":savedName.split("\\s+")[0];
        heading("VISÃO GERAL",savedName.isEmpty()?"Sua segurança":"Olá, "+firstName,MonitorService.running?"Acompanhando seu trajeto.":"Tudo pronto para o seu próximo trajeto.");
        LinearLayout hero=column();hero.setPadding(dp(18),dp(18),dp(18),dp(18));hero.setBackground(round(color(dark?"#181818":"#244E40"),18));
        LinearLayout heroRow=row();heroRow.addView(new Glyph(this,"shield",color(dark?"#EEEEEE":"#C7DEB8")),new LinearLayout.LayoutParams(dp(29),dp(31)));
        LinearLayout heroWords=column();LinearLayout.LayoutParams words=new LinearLayout.LayoutParams(0,-2,1);words.leftMargin=dp(12);heroRow.addView(heroWords,words);
        heroWords.addView(label("monitorStatus",MonitorService.running?"Monitoramento ativo":"Monitoramento pausado",17,Color.WHITE,true));
        heroWords.addView(label("monitorHint","Ative antes de começar seu trajeto.",12,color(dark?"#BBBBBB":"#CCDACD"),false));hero.addView(heroRow);
        space(hero,15);monitorButton=button(MonitorService.running?"Encerrar trajeto":"Iniciar monitoramento",color(dark?"#EEEEEE":"#E0EACF"),color("#20382D"));monitorButton.setTag("monitor");
        monitorButton.setOnClickListener(v->{if(MonitorService.running)stopMonitoring();else start("START");});hero.addView(monitorButton);addCard(hero);
        LinearLayout metrics=row();String[] keys={"speed","duration","battery"}, captions={"km/h","tempo de trajeto","bateria"};
        for(int i=0;i<3;i++) {LinearLayout metric=column();metric.setPadding(dp(i==0?0:12),dp(8),dp(8),dp(8));metric.addView(label(keys[i],"—",25,ink,true));metric.addView(text(captions[i],10,muted,false));metrics.addView(metric,new LinearLayout.LayoutParams(0,-2,1));}
        content.addView(metrics);space(content,16);
        LinearLayout route=card();sectionTitle(route,"pin","Localização","routeState");space(route,12);
        trace=new TraceView(this);route.addView(trace,new LinearLayout.LayoutParams(-1,dp(140)));space(route,10);
        route.addView(label("coordinates","Aguardando localização",13,ink,true));route.addView(label("gpsHint","Ative o GPS para acompanhar o trajeto.",11,muted,false));
        mapButton=outlineButton("Ver no mapa");mapButton.setTag("map");mapButton.setOnClickListener(v->{
            Location l=currentLocation();JSONArray points=MonitorService.running?db.points(MonitorService.tripId):new JSONArray();
            if(points.length()==0&&MonitorService.fresh(l))points.put(new JSONArray(Arrays.asList(l.getLatitude(),l.getLongitude())));
            if(points.length()==0)start("PREVIEW");else showMap(points,"Seu trajeto",routeSummary());
        });space(route,10);route.addView(mapButton);addCard(route);
        LinearLayout weather=card();LinearLayout weatherTop=row();LinearLayout forecast=column();forecast.addView(text("ANTES DE SAIR",10,muted,true));forecast.addView(label("weatherTitle","Clima no trajeto",18,ink,true));weatherTop.addView(forecast,new LinearLayout.LayoutParams(0,-2,1));weatherTop.addView(label("weatherTemp","—",34,ink,true));weather.addView(weatherTop);
        space(weather,8);weather.addView(label("weatherDetail","Ative a localização para consultar.",12,muted,false));space(weather,8);weather.addView(label("weatherWarning","",13,ink,false));space(weather,8);
        LinearLayout weatherFooter=row();TextView source=text("Open-Meteo",10,muted,false);source.setOnClickListener(v->openUrl("https://open-meteo.com/"));source.setPadding(0,dp(12),dp(8),dp(12));weatherFooter.addView(source,new LinearLayout.LayoutParams(0,-2,1));
        Button refreshWeather=smallButton("Atualizar");refreshWeather.setTag("weatherRefresh");refreshWeather.setOnClickListener(v->{Location l=currentLocation();if(!MonitorService.fresh(l))start("PREVIEW");else WeatherClient.refresh(this,l,true);});weatherFooter.addView(refreshWeather);weather.addView(weatherFooter);addCard(weather);
        LinearLayout check=card();sectionTitle(check,"history","Check-in de segurança",null);space(check,8);check.addView(label("checkIn","A cada 30 minutos",15,ink,true));check.addView(label("checkHint","Confirme que está bem durante o trajeto.",12,muted,false));space(check,10);Button okay=outlineButton("Estou bem");okay.setTag("checkIn");okay.setOnClickListener(v->{if(MonitorService.running)serviceAction("CANCEL");else selectTab(3);});check.addView(okay);addCard(check);
        LinearLayout contacts=card();sectionTitle(contacts,"person","Contatos de emergência",null);space(contacts,8);contacts.addView(label("contactStatus","",13,ink,true));Button editContacts=outlineButton("Configurar contatos e envio");editContacts.setOnClickListener(v->selectTab(3));space(contacts,10);contacts.addView(editContacts);addCard(contacts);
        TextView note=text("Você terá 30 segundos para cancelar o alerta.",11,muted,false);note.setGravity(Gravity.CENTER);note.setPadding(0,dp(8),0,0);content.addView(note);
    }
    private void sensorsScreen() {
        heading("LEITURAS DO APARELHO","Sensores",Build.MANUFACTURER+" "+Build.MODEL);
        LinearLayout graph=card();sectionTitle(graph,"wave","Aceleração",null);space(graph,8);
        graph.addView(label("g","— G",34,ink,true));graph.addView(text("Intensidade total · inclui a gravidade",11,muted,false));
        signal=new SignalView(this);graph.addView(signal,new LinearLayout.LayoutParams(-1,dp(110)));graph.addView(text("Últimas leituras reais",10,muted,false));addCard(graph);
        String[] titles={"Acelerômetro","Giroscópio","Proximidade","Luminosidade","Barômetro","Temperatura ambiente"};
        String[] details={"Impacto e movimento","Rotação do aparelho","Objeto próximo da tela; não confirma bolso ou mochila","Modo noturno automático","Variação relativa de altitude","Sensor físico, quando disponível"};
        for(int i=0;i<titles.length;i++) {
            LinearLayout sensor=card();LinearLayout top=row();top.addView(text(titles[i],15,ink,true),new LinearLayout.LayoutParams(0,-2,1));
            top.addView(text(readings.available[i]?"DISPONÍVEL":"AUSENTE",9,readings.available[i]?accent:muted,true));sensor.addView(top);
            space(sensor,8);sensor.addView(label("sensor"+i,readings.available[i]?"Aguardando leitura":"Não disponível neste aparelho",19,ink,true));
            sensor.addView(label("sensorDetail"+i,details[i],11,muted,false));addCard(sensor);
        }
        LinearLayout algorithm=card();algorithm.addView(text("Como a queda é identificada",15,ink,true));space(algorithm,8);
        algorithm.addView(text("O app combina impacto e rotação com um período de repouso. Proximidade e pressão ajudam a interpretar o contexto. Uma possível queda abre a contagem de 30 segundos.",13,muted,false));
        space(algorithm,8);algorithm.addView(text("Sem giroscópio, o limite de impacto é maior. Sensores ausentes são indicados acima.",12,muted,false));addCard(algorithm);
    }
    private void historyScreen() {
        heading("REGISTROS DO APLICATIVO","Histórico","Trajetos, alertas e confirmação de envio.");
        LinearLayout filters=row();String[] names={"Trajetos","Eventos","Alertas"};
        for(int i=0;i<3;i++){final int f=i;Button b=button(names[i],historyFilter==i?accent:soft,historyFilter==i?(dark?bg:Color.WHITE):ink);b.setTextSize(12);b.setTag("filter"+i);b.setOnClickListener(v->{historyFilter=f;build();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);lp.rightMargin=dp(i<2?6:0);filters.addView(b,lp);}
        content.addView(filters);space(content,16);historyRows=column();content.addView(historyRows);refreshHistory();
        Button panel=outlineButton("Abrir painel da API");panel.setTag("apiPanel");panel.setOnClickListener(v->openUrl(ApiClient.ENDPOINT));content.addView(panel);
        TextView explanation=text("O traçado detalhado é salvo no aparelho. A API recebe posições a cada minuto e os alertas; seus registros confirmados aparecem no painel.",11,muted,false);explanation.setPadding(0,dp(12),0,0);content.addView(explanation);
    }
    private void refreshHistory() {
        if(historyRows==null)return;historyRows.removeAllViews();historyRevision=Outbox.revision.get();
        if(historyFilter==0) {
            List<Outbox.Trip> trips=db.trips();
            if(trips.isEmpty())empty(historyRows,"Nenhum trajeto registrado","Inicie o monitoramento para registrar seu percurso.");
            for(Outbox.Trip trip:trips){
                LinearLayout c=card();LinearLayout top=row();top.addView(text(date(trip.start,"dd MMM · HH:mm"),15,ink,true),new LinearLayout.LayoutParams(0,-2,1));top.addView(chip(trip.finish==0&&MonitorService.running?"Em andamento":"Encerrado",false));c.addView(top);
                space(c,12);c.addView(text(String.format(Locale.getDefault(),"%.2f km   ·   %s",trip.distance/1000,duration((trip.finish==0?trip.last:trip.finish)-trip.start)),23,ink,true));
                space(c,6);c.addView(text(trip.points+" pontos GPS · "+trip.sent+"/"+trip.total+" registros confirmados",11,muted,false));
                space(c,10);TextView action=text("Ver trajeto  →",13,accent,true);action.setPadding(0,dp(6),0,dp(6));c.addView(action);
                c.setTag("trip"+trip.id);c.setOnClickListener(v->showMap(db.points(trip.id),"Trajeto de "+date(trip.start,"dd/MM"),String.format(Locale.getDefault(),"%.2f km · %s\n%d de %d registros confirmados no servidor",trip.distance/1000,duration((trip.finish==0?trip.last:trip.finish)-trip.start),trip.sent,trip.total)));addSpaced(historyRows,c);
            }
        } else {
            List<Outbox.Event> events=db.events(historyFilter==2);
            if(events.isEmpty())empty(historyRows,historyFilter==2?"Nenhum alerta registrado":"Nenhum evento registrado","Os registros aparecerão aqui durante o monitoramento.");
            for(Outbox.Event event:events){
                LinearLayout c=card();LinearLayout top=row();top.addView(text(date(event.at,"dd/MM · HH:mm"),11,muted,false),new LinearLayout.LayoutParams(0,-2,1));top.addView(chip(event.state,event.state.contains("Pendente")||event.state.contains("reenvio")||event.state.equals("Falhou")));c.addView(top);
                space(c,9);c.addView(text(event.title,16,event.kind==2?danger:ink,true));space(c,5);c.addView(text(event.detail,12,muted,false));
                if(event.remoteId!=null&&!event.remoteId.isEmpty()){space(c,6);c.addView(text("Servidor · registro #"+event.remoteId,11,accent,true));}
                c.setOnClickListener(v->showEvent(event));c.setTag("event"+event.id);addSpaced(historyRows,c);
            }
        }
    }
    private void showEvent(Outbox.Event event) {
        String detail=date(event.at,"dd/MM/yyyy 'às' HH:mm:ss")+"\n\n"+event.detail+"\n\nEnvio: "+event.state;
        if(event.remoteId!=null&&!event.remoteId.isEmpty())detail+="\nRegistro no servidor: "+event.remoteId;
        if(event.payload!=null){try{JSONObject sent=new JSONObject(event.payload);detail+="\n\nNome enviado: "+sent.optString("usuario")+"\nAparelho: "+sent.optString("modelo")+(sent.has("bateria")?"\nBateria: "+Math.round(sent.optDouble("bateria"))+"%":"")+(sent.has("latitude")?"\nLocalização enviada: "+sent.optDouble("latitude")+", "+sent.optDouble("longitude"):"\nLocalização indisponível no momento do registro")+(sent.has("pressao")?"\nPressão: "+sent.optDouble("pressao")+" hPa":"");}catch(JSONException ignored){}}

        String sms=db.smsSummary(event.id);if(!sms.isEmpty())detail+="\n\nContatos por SMS\n"+sms+"\n\nAceito pela operadora não confirma leitura ou entrega ao destinatário.";
        AlertDialog.Builder b=dialogBuilder().setTitle(event.title).setMessage(detail).setPositiveButton("Fechar",null);
        if(event.trip>0)b.setNeutralButton("Ver trajeto",(d,w)->showMap(db.points(event.trip),"Trajeto do evento","Registro de "+date(event.at,"dd/MM · HH:mm")));
        openedDialog=b.show();
    }
    private void settingsScreen() {
        heading("PREFERÊNCIAS DO TRAJETO","Ajustes","Socorro "+BuildConfig.VERSION_NAME+" · versão "+BuildConfig.VERSION_CODE);
        LinearLayout profile=card();profile.addView(text("Seu nome",16,ink,true));profile.addView(text("Este nome acompanha seus registros na API e os alertas aos contatos.",12,muted,false));space(profile,8);
        nameInput=input("Nome",prefs.getString("name",""),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(150)});nameInput.setTag("profileName");profile.addView(nameInput);
        Button save=outlineButton("Salvar nome no aparelho");save.setTag("saveName");save.setOnClickListener(v->{if(saveNameDraft()){nameInput.clearFocus();hideKeyboard();toast("Nome salvo no aparelho.");}});space(profile,8);profile.addView(save);addCard(profile);
        LinearLayout contacts=card();sectionTitle(contacts,"person","Contatos de emergência",null);space(contacts,8);
        JSONArray people=Contacts.read(prefs);
        if(people.length()==0)contacts.addView(text("Cadastre quem deve receber o alerta e sua localização.",12,muted,false));
        for(int i=0;i<people.length();i++){
            final int index=i;JSONObject person=people.optJSONObject(i);LinearLayout contact=row();contact.setPadding(0,dp(10),0,dp(10));LinearLayout words=column();words.addView(text(person.optString("name"),14,ink,true));words.addView(text(person.optString("phone"),12,muted,false));contact.addView(words,new LinearLayout.LayoutParams(0,-2,1));Button remove=smallButton("Remover");remove.setOnClickListener(v->dialogBuilder().setTitle("Remover contato?").setMessage(person.optString("name")).setNegativeButton("Voltar",null).setPositiveButton("Remover",(d,w)->{JSONArray saved=Contacts.read(prefs);saved.remove(index);prefs.edit().putString("contacts",saved.toString()).apply();if(saved.length()==0)prefs.edit().putBoolean("smsEnabled",false).apply();build();}).show());contact.addView(remove);contacts.addView(contact);
        }
        space(contacts,8);Button add=outlineButton("Adicionar contato");add.setTag("addContact");add.setOnClickListener(v->addContact());contacts.addView(add);space(contacts,12);
        contacts.addView(text("Enviar alertas por",14,ink,true));
        Spinner channel=new Spinner(this);channel.setTag("alertChannel");
        String[] channelLabels={"SMS automático","WhatsApp · confirmar envio","SMS + WhatsApp"};
        String[] channelValues={"sms","whatsapp","both"};
        ArrayAdapter<String> channels=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,channelLabels);channel.setAdapter(channels);
        String selected=AlertChannels.channel(prefs);int channelIndex=selected.equals("whatsapp")?1:selected.equals("both")?2:0;channel.setSelection(channelIndex);
        channel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(v instanceof TextView)((TextView)v).setTextColor(ink);if(!channelValues[pos].equals(AlertChannels.channel(prefs))){prefs.edit().putString("alertChannel",channelValues[pos]).apply();build();}}});contacts.addView(channel,new LinearLayout.LayoutParams(-1,dp(52)));
        contacts.addView(text("SMS pode ser enviado automaticamente após os 30 segundos. WhatsApp prepara a mensagem para cada contato: você precisa abrir e tocar em Enviar. Para ajuda automática, escolha SMS ou SMS + WhatsApp e habilite o SMS.",12,muted,false));space(contacts,10);
        smsSwitch=toggle("Autorizar SMS automático",prefs.getBoolean("smsEnabled",false));smsSwitch.setEnabled(!selected.equals("whatsapp"));smsSwitch.setTag("smsEnabled");contacts.addView(smsSwitch);space(contacts,6);
        contacts.addView(text("Após 30 segundos sem cancelamento, os contatos recebem um SMS com o motivo e a localização disponível. Requer permissão, chip e serviço da operadora. Pode haver cobrança.",11,muted,false));
        if(prefs.getBoolean("smsEnabled",false)&&checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED){space(contacts,6);contacts.addView(text("Permissão de SMS indisponível. O envio para contatos não está ativo.",12,danger,true));}
        smsSwitch.setOnCheckedChangeListener((v,checked)->{
            if(rendering)return;
            if(!checked){prefs.edit().putBoolean("smsEnabled",false).apply();return;}
            if(Contacts.read(prefs).length()==0){smsSwitch.setChecked(false);toast("Cadastre um contato primeiro");return;}
            if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)){smsSwitch.setChecked(false);toast("Este aparelho não oferece envio por SMS");return;}
            if(checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED)prefs.edit().putBoolean("smsEnabled",true).apply();
            else requestPermissions(new String[]{Manifest.permission.SEND_SMS},2);
        });addCard(contacts);
        LinearLayout check=card();check.addView(text("Check-in de segurança",16,ink,true));space(check,6);check.addView(text("Durante o monitoramento, o app pergunta se você está bem. Sem resposta em 30 segundos, registra um alerta e tenta avisar os contatos habilitados.",12,muted,false));space(check,10);
        Spinner interval=new Spinner(this);String[] choices={"Desativado","A cada 15 minutos","A cada 30 minutos","A cada 60 minutos"};int[] minutes={0,15,30,60};ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices);interval.setAdapter(adapter);int selection=2;for(int i=0;i<minutes.length;i++)if(minutes[i]==prefs.getInt("checkInMinutes",30))selection=i;interval.setSelection(selection);interval.setTag("checkInterval");
        interval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(v instanceof TextView)((TextView)v).setTextColor(ink);int next=minutes[pos];if(next!=prefs.getInt("checkInMinutes",30)){prefs.edit().putInt("checkInMinutes",next).apply();if(MonitorService.running)serviceAction("SETTINGS");}}});check.addView(interval,new LinearLayout.LayoutParams(-1,dp(52)));addCard(check);
        LinearLayout display=card();display.addView(text("Aparência",16,ink,true));space(display,8);
        Spinner themePicker=new Spinner(this);themePicker.setTag("themeMode");
        String[] themeNames={"Automático · luminosidade","Claro","Escuro · preto","Seguir o Android"};
        String[] themeValues={"auto","light","dark","system"};
        themePicker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,themeNames));
        int themeSelection=0;for(int i=0;i<themeValues.length;i++)if(themeValues[i].equals(themeMode()))themeSelection=i;themePicker.setSelection(themeSelection);
        themePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(v instanceof TextView)((TextView)v).setTextColor(ink);if(!themeValues[pos].equals(themeMode())){prefs.edit().putString("themeMode",themeValues[pos]).putBoolean("autoNight",pos==0).apply();lightThemeInitialized=false;updateLightTheme(SystemClock.elapsedRealtime());}}});display.addView(themePicker,new LinearLayout.LayoutParams(-1,dp(52)));
        space(display,8);display.addView(label("themeDiagnostic","Aguardando sensor de luminosidade",12,ink,false));space(display,8);
        display.addView(label("themeThreshold","",12,muted,false));SeekBar threshold=new SeekBar(this);threshold.setTag("themeThreshold");threshold.setMax(49);threshold.setProgress(themeThreshold()/10-1);
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){lightThemeInitialized=false;updateLightTheme(SystemClock.elapsedRealtime());}public void onProgressChanged(SeekBar bar,int progress,boolean user){if(user){prefs.edit().putInt("themeThreshold",(progress+1)*10).apply();set("themeThreshold","Limite do automático: "+themeThreshold()+" lux");}}});display.addView(threshold,new LinearLayout.LayoutParams(-1,dp(48)));
        display.addView(text("No automático, ambientes abaixo do limite ficam pretos; acima ficam claros. Uma margem de 10 lux evita piscadas. Ajuste o limite se o ambiente continuar claro ou escuro demais. Não é necessário iniciar o monitoramento.",12,muted,false));addCard(display);
        LinearLayout permissions=card();permissions.addView(text("Permissões",16,ink,true));space(permissions,8);permissions.addView(text("Localização: "+(hasLocation()?"permitida":"não permitida")+"\nNotificações: "+(notificationsAllowed()?"permitidas":"desativadas")+"\nSMS: "+(checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED?"permitido":"não permitido"),12,muted,false));Button settings=outlineButton("Abrir configurações do Android");space(permissions,10);settings.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));permissions.addView(settings);addCard(permissions);
        LinearLayout background=card();background.addView(text("Monitoramento em segundo plano",16,ink,true));space(background,8);
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        background.addView(text("O monitoramento iniciado continua ao sair do app e apagar a tela. A notificação permite encerrar o trajeto. Forçar parada, reiniciar o celular ou restrições do sistema podem interrompê-lo.",12,muted,false));space(background,8);
        background.addView(label("backgroundHealth","",12,ink,true));
        background.addView(text("Bateria: "+(power.isIgnoringBatteryOptimizations(getPackageName())?"sem otimização para este app":"otimização do Android ativa")+"\nEconomia de energia: "+(power.isPowerSaveMode()?"ativada":"desativada")+"\nNo Samsung: Configurações → Aplicativos → Socorro → Bateria → Não restrito. Remova o Socorro dos aplicativos suspensos, se necessário. O monitoramento contínuo consome bateria.",12,muted,false));
        Button batterySettings=outlineButton("Configurar bateria em segundo plano");batterySettings.setTag("backgroundBattery");batterySettings.setOnClickListener(v->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}});space(background,10);background.addView(batterySettings);addCard(background);
        LinearLayout scope=card();scope.addView(text("Sobre o monitoramento",15,ink,true));space(scope,8);scope.addView(text("Os alertas e as posições são enviados à API Socorro. O registro no servidor não aciona ambulância. O detector é um protótipo acadêmico e os sensores variam entre aparelhos.",12,muted,false));space(scope,8);Button panel=outlineButton("Ver registros no servidor");panel.setOnClickListener(v->openUrl(ApiClient.ENDPOINT));scope.addView(panel);addCard(scope);
    }
    private void addContact() {
        if(Contacts.read(prefs).length()>=5){toast("Você já cadastrou cinco contatos");return;}
        LinearLayout fields=column();fields.setPadding(dp(24),dp(8),dp(24),dp(8));EditText name=input("Nome do contato","",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});name.setTag("contactName");fields.addView(name);EditText phone=input("Telefone com DDD","",android.text.InputType.TYPE_CLASS_PHONE);phone.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});phone.setTag("contactPhone");fields.addView(phone);fields.addView(text("Exemplo: 11912345678 ou +5511912345678",11,muted,false));
        AlertDialog dialog=dialogBuilder().setTitle("Novo contato").setView(fields).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null).create();openedDialog=dialog;dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String n=name.getText().toString().trim(),p=Contacts.phone(phone.getText().toString());
            if(n.isEmpty()){name.setError("Informe o nome");return;}if(!Contacts.validPhone(p)){phone.setError("Use DDD e número, ou +código do país");return;}
            JSONArray list=Contacts.read(prefs);if(Contacts.duplicate(list,p)){phone.setError("Número já cadastrado");return;}
            try{list.put(new JSONObject().put("name",n).put("phone",p));prefs.edit().putString("contacts",list.toString()).apply();dialog.dismiss();build();}catch(JSONException e){toast("Não foi possível salvar o contato");}
        });
    }
    private void makeAlert() {
        alertBox=column();alertBox.setPadding(dp(20),dp(15),dp(20),dp(14));alertBox.setBackgroundColor(color("#9D3D2F"));
        LinearLayout top=row();LinearLayout words=column();alertTitle=text("Você está bem?",21,Color.WHITE,true);words.addView(alertTitle);alertDetail=text("",12,color("#FFE6DD"),false);words.addView(alertDetail);top.addView(words,new LinearLayout.LayoutParams(0,-2,1));alertClock=text("00:30",30,Color.WHITE,true);alertClock.setTag("alertCountdown");top.addView(alertClock);alertBox.addView(top);space(alertBox,8);
        alertProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);alertProgress.setMax(30);alertProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(color("#F5D5B6")));alertProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(color("#B86758")));alertBox.addView(alertProgress,new LinearLayout.LayoutParams(-1,dp(4)));space(alertBox,10);
        Button cancel=button("Estou bem · cancelar alerta",color("#FFF7EC"),color("#823226"));cancel.setTag("cancelAlert");cancel.setOnClickListener(v->serviceAction("CANCEL"));alertBox.addView(cancel);alertBox.setVisibility(View.GONE);root.addView(alertBox);
    }
    private void update() {
        if(root==null)return;
        long now=SystemClock.elapsedRealtime();
        boolean alert=MonitorService.running&&MonitorService.alertDeadline>0;
        alertBox.setVisibility(alert?View.VISIBLE:View.GONE);
        if (panicDock != null) panicDock.setVisibility(alert?View.GONE:View.VISIBLE);
        if(alert){int seconds=(int)Math.max(0,(MonitorService.alertDeadline-now+999)/1000);alertClock.setText(String.format(Locale.getDefault(),"00:%02d",seconds));alertProgress.setProgress(seconds);alertTitle.setText(MonitorService.alertReason==SafetyTimer.CHECK_IN?"Você está bem?":"Alerta de emergência");alertDetail.setText(MonitorService.alertReason==SafetyTimer.CHECK_IN?"Confirme para continuar o trajeto.":MonitorService.reasonTitle(MonitorService.alertReason));}
        if(whatsAppButton!=null){int pending=AlertChannels.pending(prefs).length();whatsAppButton.setVisibility(pending>0?View.VISIBLE:View.GONE);whatsAppButton.setText("WhatsApp · "+pending+" alerta(s) para enviar");}
        if(tab==3)set("backgroundHealth",!MonitorService.running?"Monitoramento parado":now-MonitorService.lastTickAt<3000?"Serviço ativo · "+(MonitorService.lastAccelerationAt>0&&now-MonitorService.lastAccelerationAt<5000?"acelerômetro recebendo dados":"aguardando acelerômetro"):"Serviço sem atualização recente");
        set("live",MonitorService.running?"EM TRAJETO":"PAUSADO");
        if(tab==0){
            set("monitorStatus",MonitorService.running?"Monitoramento ativo":"Monitoramento pausado");set("monitorHint",MonitorService.running?(!notificationsAllowed()?"Ative as notificações nos ajustes do Android.":readings.available[0]?"Sensores acompanhando seu movimento.":"Sem acelerômetro. Use o SOS manual."):"Ative antes de começar seu trajeto.");
            monitorButton.setText(MonitorService.running?"Encerrar trajeto":"Iniciar monitoramento");
            Location l=currentLocation();boolean fresh=MonitorService.fresh(l);
            set("speed",fresh&&l.hasSpeed()?String.format(Locale.getDefault(),"%.0f",l.getSpeed()*3.6):"—");set("duration",MonitorService.running?duration(now-MonitorService.startedAt):"00:00");set("battery",battery>=0?battery+"%":"—");
            set("routeState",fresh?"GPS atualizado":"SEM GPS");set("coordinates",fresh?String.format(Locale.getDefault(),"%.5f, %.5f",l.getLatitude(),l.getLongitude()):"Localização indisponível");
            boolean gps=locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)||locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            set("gpsHint",fresh?(l.hasAccuracy()?String.format(Locale.getDefault(),"Precisão de ±%.0f m · %s",l.getAccuracy(),date(l.getTime(),"HH:mm:ss")):"Precisão não informada"):!hasLocation()?"Permita localização para acompanhar o trajeto.":!gps?"Ative a localização nas configurações do Android.":"Aguardando uma posição recente do aparelho.");
            mapButton.setText(fresh?"Ver no mapa":!hasLocation()?"Permitir localização":!gps?"Ativar localização":"Atualizar localização");
            int minutes=prefs.getInt("checkInMinutes",30);long left=MonitorService.nextCheckIn-now;
            set("checkIn",minutes==0?"Check-in desativado":MonitorService.running&&left>0?"Próximo em "+duration(left):"A cada "+minutes+" minutos");
            set("checkHint",MonitorService.running?"Sem resposta em 30 segundos, um alerta é enviado.":"O lembrete funciona durante o monitoramento.");
            boolean sms=AlertChannels.usesSms(prefs)&&prefs.getBoolean("smsEnabled",false)&&checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED;
            int people=Contacts.read(prefs).length();set("contactStatus",people==0?"Nenhum contato cadastrado":people+(people==1?" contato":" contatos")+" · "+(AlertChannels.channel(prefs).equals("sms")?"SMS "+(sms?"ativado":"desativado"):AlertChannels.channel(prefs).equals("both")?"SMS "+(sms?"ativado":"desativado")+" + WhatsApp manual":"WhatsApp manual"));
            long weatherAt=prefs.getLong("weatherAt",0);boolean loading=prefs.getBoolean("weatherLoading",false);String error=prefs.getString("weatherError","");
            boolean weatherHere=true;if(fresh&&weatherAt>0){float[] separation=new float[1];Location.distanceBetween(l.getLatitude(),l.getLongitude(),Double.longBitsToDouble(prefs.getLong("weatherLat",0)),Double.longBitsToDouble(prefs.getLong("weatherLon",0)),separation);weatherHere=separation[0]<10000;}
            boolean stale=System.currentTimeMillis()-weatherAt>1800000||!weatherHere;
            set("weatherTemp",weatherAt>0?prefs.getString("weatherTemp","—"):"—");set("weatherTitle",weatherAt>0?prefs.getString("weatherDescription","Clima no trajeto"):"Clima no trajeto");
            set("weatherDetail",weatherAt>0?prefs.getString("weatherDetail","")+"\n"+(loading?"Atualizando…":(stale?"Dados anteriores · ":"Atualizado às ")+date(weatherAt,"HH:mm")):loading?"Consultando previsão…":"Ative a localização para consultar.");
            set("weatherWarning",!error.isEmpty()?error:weatherAt>0?(stale?"Atualize a previsão antes de sair.":prefs.getString("weatherWarning","")):"Previsão de chuva e vento para as próximas horas.");
            TextView warning=labels.get("weatherWarning");if(warning!=null)warning.setTextColor(prefs.getBoolean("weatherRisk",false)||stale||!error.isEmpty()?danger:muted);
        }
        if(tab==1){
            set("g",finite(readings.g,"%.2f G"));
            set("sensor0",readings.available[0]?finite(readings.g,"%.2f G"):"Não disponível");
            if(Double.isFinite(readings.g))set("sensorDetail0",String.format(Locale.getDefault(),"X %.2f · Y %.2f · Z %.2f m/s²",readings.acceleration[0],readings.acceleration[1],readings.acceleration[2]));
            set("sensor1",readings.available[1]?finite(readings.gyro,"%.2f rad/s"):"Não disponível");
            if(Double.isFinite(readings.gyro))set("sensorDetail1",String.format(Locale.getDefault(),"X %.2f · Y %.2f · Z %.2f rad/s",readings.rotation[0],readings.rotation[1],readings.rotation[2]));
            SensorReadings proximityReadings=MonitorService.running&&MonitorService.currentReadings!=null?MonitorService.currentReadings:readings;
            boolean proximityValid=proximityReadings.hasProximityReading();
            set("sensor2",!proximityReadings.available[2]?"Sensor não disponibilizado":!proximityReadings.listening[2]?"Leitura não iniciada":!proximityValid?"Aguardando leitura":proximityReadings.covered?"Objeto próximo":"Objeto afastado");
            set("sensorDetail2",!proximityReadings.available[2]?"O detector continua usando acelerômetro e giroscópio.":proximityReadings.proximitySensorName+" · "+(proximityValid?"indicação de perto/longe, sem distância precisa":"o detector ignora proximidade enquanto não houver leitura válida")+(proximityReadings.virtualProximity?". Sensor virtual: apenas informativo, sem alterar o limite de queda.":". A proximidade é um indício; não identifica bolso ou mochila."));
            set("sensor3",readings.available[3]?finite(readings.light,"%.0f lx"):"Não disponível");set("sensorDetail3",themeDescription());
            set("sensor4",!readings.available[4]?"Sem barômetro neste aparelho":Float.isFinite(readings.pressure)?finite(readings.pressure,"%.1f hPa"):readings.pressureListening?"Aguardando pressão":"Leitura não iniciada");
            set("sensorDetail4",!readings.available[4]?"Sensor opcional. O Android não disponibilizou um barômetro. A detecção continua com acelerômetro e giroscópio.":Float.isFinite(readings.pressure)?String.format(Locale.getDefault(),"%s · variação relativa: %.1f m",readings.pressureSensorName,-readings.altitudeDrop):readings.pressureListening?readings.pressureSensorName+" · aguardando a primeira leitura em hPa":readings.pressureSensorName+" · não foi possível ativar a leitura. Reabra o aplicativo.");
            set("sensor5",!readings.available[5]?"Sem sensor neste aparelho":!readings.listening[5]?"Leitura não iniciada":Float.isFinite(readings.temperature)?finite(readings.temperature,"%.1f °C"):"Aguardando leitura");
            set("sensorDetail5",!readings.available[5]?"Este aparelho não oferece um termômetro de ambiente. A temperatura do clima, no Início, vem da previsão online.":"Temperatura medida pelo sensor físico em °C.");
            if(signal!=null&&now-lastGraph>=150){signal.add(readings.g,dark);lastGraph=now;}
        }
        if(now-lastSlow>=2000){lastSlow=now;
            Intent state=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(state!=null&&state.getIntExtra(BatteryManager.EXTRA_SCALE,0)>0)battery=100*state.getIntExtra(BatteryManager.EXTRA_LEVEL,0)/state.getIntExtra(BatteryManager.EXTRA_SCALE,100);
            if(tab==0){JSONArray p=MonitorService.running?db.points(MonitorService.tripId):new JSONArray();Location l=currentLocation();if(p.length()==0&&MonitorService.fresh(l))p.put(new JSONArray(Arrays.asList(l.getLatitude(),l.getLongitude())));if(trace!=null)trace.update(p,dark);}
            if(tab==2&&historyRevision!=Outbox.revision.get())refreshHistory();
        }
        if(tab==3){set("themeDiagnostic",themeDescription());set("themeThreshold","Limite do automático: "+themeThreshold()+" lux");}
        updateLightTheme(now);
    }
    private String themeMode(){return prefs.getString("themeMode",prefs.getBoolean("autoNight",true)?"auto":"system");}
    private int themeThreshold(){return Math.max(10,Math.min(500,prefs.getInt("themeThreshold",60)));}
    private String themeDescription(){
        String mode=themeMode();String source=mode.equals("auto")?"Automático por luminosidade":mode.equals("dark")?"Escuro fixo":mode.equals("light")?"Claro fixo":"Seguindo o Android";
        String sensor=!readings.available[3]?"sensor de luz ausente":!readings.listening[3]?"sensor sem leitura ativa":!Float.isFinite(readings.light)?"aguardando primeira leitura":String.format(Locale.getDefault(),"sensor: %.1f lux",readings.light);
        return source+" · "+(dark?"fundo preto":"fundo claro")+"\n"+sensor+(mode.equals("auto")?" · limite "+themeThreshold()+" lux":"");
    }
    private void updateLightTheme(long now){
        String mode=themeMode();
        if(mode.equals("dark")){applyNight(true);return;}
        if(mode.equals("light")){applyNight(false);return;}
        if(mode.equals("system")||!readings.available[3]){applyNight((getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);return;}
        if(!Float.isFinite(readings.light)||readings.light<0)return;
        int threshold=themeThreshold();
        boolean desired=lightThemeInitialized?(dark?readings.light<threshold+10:readings.light<threshold-10):readings.light<threshold;
        lightThemeInitialized=true;
        if(desired!=dark)applyNight(desired);
    }
    private final Runnable refresh=new Runnable(){@Override public void run(){if(!resumed)return;update();handler.postDelayed(this,100);}};
    private void applyNight(boolean next){
        if(next==dark)return;
        int y=scroll==null?0:scroll.getScrollY();
        String draft=nameInput==null?null:nameInput.getText().toString();
        boolean editing=nameInput!=null&&nameInput.hasFocus();
        int selectionStart=nameInput==null?-1:nameInput.getSelectionStart();
        int selectionEnd=nameInput==null?-1:nameInput.getSelectionEnd();
        dark=next;build();
        if(draft!=null&&nameInput!=null){
            nameInput.setText(draft);
            if(editing){
                nameInput.requestFocus();
                nameInput.setSelection(Math.max(0,Math.min(selectionStart,draft.length())),Math.max(0,Math.min(selectionEnd,draft.length())));
            }
        }
        scroll.post(()->scroll.scrollTo(0,y));
    }
    private void showWhatsAppAlerts(){
        JSONArray alerts=AlertChannels.pending(prefs);ArrayList<String> names=new ArrayList<>();ArrayList<JSONObject> selectedAlerts=new ArrayList<>(),selectedContacts=new ArrayList<>();
        for(int i=0;i<alerts.length();i++){JSONObject alert=alerts.optJSONObject(i);if(alert==null)continue;JSONArray contacts=alert.optJSONArray("contacts");if(contacts==null)continue;for(int j=0;j<contacts.length();j++){JSONObject person=contacts.optJSONObject(j);if(person==null)continue;names.add(person.optString("name")+" · "+date(alert.optLong("at"),"dd/MM HH:mm"));selectedAlerts.add(alert);selectedContacts.add(person);}}
        if(names.isEmpty()){toast("Nenhuma mensagem pendente");return;}
        openedDialog=dialogBuilder().setTitle("WhatsApp · escolha o contato").setItems(names.toArray(new String[0]),(d,index)->{
            JSONObject alert=selectedAlerts.get(index),person=selectedContacts.get(index);
            Intent intent=new Intent(Intent.ACTION_VIEW,AlertChannels.whatsAppUri(person.optString("phone"),alert.optString("message")));
            boolean opened=false;for(String target:new String[]{"com.whatsapp","com.whatsapp.w4b"}){try{startActivity(intent.setPackage(target));opened=true;break;}catch(ActivityNotFoundException ignored){}}
            if(opened){db.event(0,0,"WhatsApp aberto",person.optString("name")+". Mensagem preparada; envio deve ser confirmado no WhatsApp.","Aguardando envio manual");toast("Confirme o envio no WhatsApp. Depois selecione os demais contatos.");}else toast("Instale o WhatsApp ou WhatsApp Business para enviar.");
        }).setNegativeButton("Fechar",null).setNeutralButton("Limpar pendências",(d,w)->{
            openedDialog=dialogBuilder().setTitle("Limpar mensagens pendentes?").setMessage("Isso não confirma a entrega das mensagens. Limpe apenas quando não precisar mais enviá-las.").setNegativeButton("Voltar",null).setPositiveButton("Limpar",(a,b)->{AlertChannels.clear(this);update();}).show();
        }).show();
    }
    private Location currentLocation(){return MonitorService.running?MonitorService.latestLocation:previewLocation;}
    private void requestLocation(){
        if(!hasLocation())return;
        try{boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
            for(String provider:locationManager.getProviders(true)){
                if(!provider.equals(LocationManager.GPS_PROVIDER)&&!provider.equals(LocationManager.NETWORK_PROVIDER))continue;
                if(provider.equals(LocationManager.GPS_PROVIDER)&&!fine)continue;
                locationManager.requestLocationUpdates(provider,5000,5,this);
                Location last=locationManager.getLastKnownLocation(provider);if(last!=null&&MonitorService.fresh(last))onLocationChanged(last);
            }
        }catch(SecurityException e){toast("Permita o acesso à localização");}
    }
    @Override public void onLocationChanged(Location location){if(MonitorService.fresh(location)){previewLocation=new Location(location);WeatherClient.refresh(this,location,false);}}
    private boolean hasLocation(){return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private boolean notificationsAllowed(){return getSystemService(NotificationManager.class).areNotificationsEnabled();}
    private void start(String action){
        if(!"PREVIEW".equals(action)&&nameInput!=null&&!saveNameDraft())return;
        if(!"PREVIEW".equals(action)&&prefs.getString("name","").isEmpty()){selectTab(3);nameInput.requestFocus();toast("Salve seu nome para iniciar");return;}
        ArrayList<String> permissions=new ArrayList<>();if(!hasLocation()){permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);}
        if(!"PREVIEW".equals(action)&&Build.VERSION.SDK_INT>=33&&!prefs.getBoolean("notificationAsked",false)&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){permissions.add(Manifest.permission.POST_NOTIFICATIONS);prefs.edit().putBoolean("notificationAsked",true).apply();}
        if(!permissions.isEmpty()){pendingAction=action;requestPermissions(permissions.toArray(new String[0]),1);return;}
        if("PREVIEW".equals(action)){
            if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)&&!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));return;}requestLocation();toast("Buscando localização atual");return;
        }
        try{startForegroundService(new Intent(this,MonitorService.class).setAction(action));if(tab!=0)selectTab(0);handler.postDelayed(()->{if(resumed)build();},300);}
        catch(SecurityException|IllegalStateException e){toast("Não foi possível iniciar. Confira as permissões do Android.");}
    }
    private void stopMonitoring(){
        dialogBuilder().setTitle("Encerrar trajeto?").setMessage(MonitorService.alertDeadline>0?"A contagem será cancelada e o monitoramento será encerrado.":"Seu trajeto será salvo. Sensores e alertas automáticos serão interrompidos.").setNegativeButton("Continuar",null).setPositiveButton("Encerrar",(d,w)->{stopService(new Intent(this,MonitorService.class));handler.postDelayed(()->{if(resumed)build();},200);}).show();
    }
    private void serviceAction(String action){if(MonitorService.running)startService(new Intent(this,MonitorService.class).setAction(action));}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==2){boolean allowed=checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED;prefs.edit().putBoolean("smsEnabled",allowed).apply();if(!allowed)dialogBuilder().setTitle("SMS não habilitado").setMessage("O Android não concedeu a permissão. Os alertas continuam sendo registrados no servidor, mas os contatos não receberão SMS. Confira a permissão nas configurações do aplicativo.").setPositiveButton("Entendi",null).show();if(tab==3)build();}
        if(request==1){String action=pendingAction;pendingAction=null;if(hasLocation()){requestLocation();if(action!=null)start(action);}else toast("Localização é necessária. Você pode permitir nos ajustes do Android.");}
    }
    private void showMap(JSONArray points,String title,String summary){
        Dialog dialog=new Dialog(this,dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);openedDialog=dialog;
        LinearLayout layout=column();layout.setBackgroundColor(bg);
        layout.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout bar=row();bar.setPadding(dp(12),dp(10),dp(20),dp(10));Button back=smallButton("Voltar");back.setOnClickListener(v->dialog.dismiss());bar.addView(back);TextView heading=text(title,19,ink,true);heading.setPadding(dp(16),0,0,0);bar.addView(heading);layout.addView(bar);
        if(points.length()>0){
            RouteMapView map=new RouteMapView(this,points,dark);map.setTag("routeMap");layout.addView(map,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout controls=row();controls.setPadding(dp(16),dp(6),dp(16),0);
            Button minus=smallButton("−");minus.setContentDescription("Afastar mapa");minus.setOnClickListener(v->map.zoomBy(-1));controls.addView(minus,new LinearLayout.LayoutParams(dp(52),dp(48)));
            Button center=smallButton("Centralizar trajeto");center.setOnClickListener(v->map.fit());LinearLayout.LayoutParams centerLp=new LinearLayout.LayoutParams(0,dp(48),1);centerLp.setMargins(dp(8),0,dp(8),0);controls.addView(center,centerLp);
            Button plus=smallButton("+");plus.setContentDescription("Aproximar mapa");plus.setOnClickListener(v->map.zoomBy(1));controls.addView(plus,new LinearLayout.LayoutParams(dp(52),dp(48)));layout.addView(controls);
        }else{LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty(empty,"Sem pontos de GPS","O trajeto ainda não tem posições com precisão suficiente.");layout.addView(empty,new LinearLayout.LayoutParams(-1,0,1));}
        LinearLayout info=column();info.setPadding(dp(22),dp(12),dp(22),dp(14));info.addView(text(summary,14,ink,true));space(info,7);info.addView(text("Verde: início · vermelho: última posição",11,muted,false));
        TextView source=text("© OpenStreetMap · ruas requerem internet",11,muted,false);source.setPadding(0,dp(8),0,dp(8));source.setOnClickListener(v->openUrl("https://www.openstreetmap.org/copyright"));info.addView(source);layout.addView(info);
        dialog.setContentView(layout);Window window=dialog.getWindow();if(window!=null){if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false);window.setStatusBarColor(bg);window.setNavigationBarColor(bg);}
        dialog.show();if(window!=null){window.setLayout(-1,-1);window.getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}layout.requestApplyInsets();
    }
    private String routeSummary(){List<Outbox.Trip> trips=db.trips();if(MonitorService.running&&!trips.isEmpty()){Outbox.Trip t=trips.get(0);return String.format(Locale.getDefault(),"%.2f km · %s\n%d pontos GPS · %d/%d registros confirmados",t.distance/1000,duration(SystemClock.elapsedRealtime()-MonitorService.startedAt),t.points,t.sent,t.total);}Location l=currentLocation();return MonitorService.fresh(l)?String.format(Locale.getDefault(),"%.5f, %.5f\nPrecisão de ±%.0f m",l.getLatitude(),l.getLongitude(),l.getAccuracy()):"Localização atual";}
    @Override protected void onResume(){super.onResume();resumed=true;for(int type:SensorReadings.TYPES){Sensor sensor=SensorReadings.sensor(sensorManager,type);if(sensor!=null){boolean listening=sensorManager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_UI);for(int i=0;i<SensorReadings.TYPES.length;i++)if(SensorReadings.TYPES[i]==type)readings.listening[i]=listening;if(type==Sensor.TYPE_PRESSURE)readings.pressureListening=listening;}}requestLocation();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(refresh);sensorManager.unregisterListener(this);java.util.Arrays.fill(readings.listening,false);locationManager.removeUpdates(this);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(openedDialog!=null&&openedDialog.isShowing())openedDialog.dismiss();db.close();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("tab",tab);state.putInt("filter",historyFilter);super.onSaveInstanceState(state);}
    
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){if(tab!=0)selectTab(0);else super.onBackPressed();}
    @android.annotation.TargetApi(33)
    private static final class BackSupport {
        private final MainActivity activity;
        private final android.window.OnBackInvokedCallback callback;
        private boolean registered;
        BackSupport(MainActivity activity){this.activity=activity;callback=()->activity.selectTab(0);}
        void update(boolean enabled){
            if(registered==enabled)return;
            if(enabled)activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);
            else activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
            registered=enabled;
        }
    }
    @Override public void onSensorChanged(SensorEvent event){readings.accept(event);if(event.sensor.getType()==Sensor.TYPE_LIGHT)updateLightTheme(SystemClock.elapsedRealtime());}
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    @Override public void onProviderEnabled(String provider){}
    @Override public void onProviderDisabled(String provider){}
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}

    private int color(String value){return Color.parseColor(value);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable shape=round(card,16);shape.setStroke(dp(1),line);c.setBackground(shape);return c;}
    private void addCard(View c){addSpaced(content,c);}
    private void addSpaced(LinearLayout parent,View child){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);parent.addView(child,lp);}
    private void space(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private TextView text(String value,int size,int color,boolean medium){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1);return t;}
    private TextView label(String key,String value,int size,int color,boolean medium){TextView t=text(value,size,color,medium);labels.put(key,t);t.setTag(key);return t;}
    private void set(String key,String value){TextView t=labels.get(key);if(t!=null&&!t.getText().toString().equals(value))t.setText(value);}
    private Button button(String value,int background,int foreground){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(foreground);b.setMinHeight(dp(52));b.setMinimumHeight(dp(52));b.setPadding(dp(14),dp(10),dp(14),dp(10));b.setStateListAnimator(null);b.setElevation(0);b.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(color(dark?"#33FFFFFF":"#22334422")),round(background,12),null));return b;}
    private Button outlineButton(String value){Button b=button(value,card,accent);GradientDrawable shape=round(card,12);shape.setStroke(dp(1),line);b.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(soft),shape,null));return b;}
    private Button smallButton(String value){Button b=button(value,soft,accent);b.setTextSize(11);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(8),dp(12),dp(8));return b;}
    private TextView chip(String value,boolean caution){TextView t=text(value,10,caution?danger:accent,true);t.setPadding(dp(9),dp(5),dp(9),dp(5));t.setBackground(round(soft,7));return t;}
    private void sectionTitle(LinearLayout parent,String glyph,String title,String stateKey){LinearLayout l=row();l.addView(new Glyph(this,glyph,accent),new LinearLayout.LayoutParams(dp(18),dp(20)));TextView t=text(title,14,ink,true);t.setPadding(dp(8),0,dp(5),0);l.addView(t,new LinearLayout.LayoutParams(0,-2,1));if(stateKey!=null)l.addView(label(stateKey,"",9,muted,true));parent.addView(l);}
    private EditText input(String hint,String value,int type){EditText edit=new EditText(this);edit.setTextColor(ink);edit.setHintTextColor(muted);edit.setHint(hint);edit.setTextSize(15);edit.setInputType(type);edit.setSingleLine(true);edit.setText(value);edit.setMinHeight(dp(56));edit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));return edit;}
    private Switch toggle(String title,boolean checked){Switch s=new Switch(this);s.setText(title);s.setTextSize(14);s.setTextColor(ink);s.setPadding(0,dp(6),0,dp(6));s.setMinHeight(dp(52));s.setChecked(checked);s.setSwitchPadding(dp(12));s.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,muted}));return s;}
    private void empty(LinearLayout parent,String title,String detail){LinearLayout box=column();box.setPadding(dp(10),dp(32),dp(10),dp(32));box.setGravity(Gravity.CENTER);box.addView(new Glyph(this,"history",muted),new LinearLayout.LayoutParams(dp(32),dp(32)));space(box,16);box.addView(text(title,17,ink,true));space(box,7);TextView sub=text(detail,12,muted,false);sub.setGravity(Gravity.CENTER);box.addView(sub);parent.addView(box);}
    private String finite(double value,String format){return Double.isFinite(value)?String.format(Locale.getDefault(),format,value):"Aguardando leitura";}
    private String date(long value,String format){return new SimpleDateFormat(format,new Locale("pt","BR")).format(new Date(value));}
    static String duration(long millis){long seconds=Math.max(0,millis/1000);return seconds>=3600?String.format(Locale.getDefault(),"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.getDefault(),"%02d:%02d",seconds/60,seconds%60);}
    private void openUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){toast("Nenhum navegador disponível");}}
    private AlertDialog.Builder dialogBuilder(){return new AlertDialog.Builder(this,dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void hideKeyboard(){android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);keyboard.hideSoftInputFromWindow(root.getWindowToken(),0);}
}





