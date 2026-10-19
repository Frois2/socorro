package br.com.socorro;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.location.Location;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SocorroTest {
    private Instrumentation instrumentation;
    private Context context;
    @Before public void setup() {
        instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();
        ApiClient.transport=(method,url,body)->{
            if(method.equals("POST")){
                JSONObject request=new JSONObject(body);
                assertTrue(request.getInt("tipo_registro")==1||request.getInt("tipo_registro")==2);
                return new JSONObject("{\"status\":\"sucesso\",\"id\":42}");
            }
            return new JSONObject("{\"current\":{\"temperature_2m\":24,\"precipitation\":0,\"weather_code\":2,\"wind_speed_10m\":12,\"wind_gusts_10m\":18},\"hourly\":{\"precipitation_probability\":[10,20,15]}}");
        };
        
        context.getSharedPreferences("socorro",0).edit().putBoolean("smsEnabled",false).putString("alertChannel","sms").remove("whatsappPending").putBoolean("autoNight",false).remove("themeMode").remove("themeThreshold").putString("name","Teste local").putString("contacts","[]").apply();
    }
    @After public void cleanup() throws Exception {
        context.stopService(new Intent(context,MonitorService.class));waitFor(()->!MonitorService.running,5000);
        MonitorService.nextCheckIn=0;
    }
    @Test public void queuePersistsPrioritizesAndConfirms() throws Exception {
        String file="queue-test.db";context.deleteDatabase(file);
        long alert;
        try(Outbox db=new Outbox(context,file)){
            db.add(1,"{\"tipo_registro\":1}",0,"Normal","Local");
            alert=db.add(2,"{\"tipo_registro\":2}",0,"SOS","Local");assertEquals(2,db.pendingCount());
        }
        try(Outbox db=new Outbox(context,file)){
            Outbox.Entry entry=db.first();assertEquals(2,entry.type);assertEquals(alert,entry.event);
            db.failed(entry);assertEquals(2,db.pendingCount());assertEquals("Aguardando reenvio",db.events(true).get(0).state);
            db.confirmed(entry,"105");assertEquals(1,db.pendingCount());assertEquals(1,db.first().type);
            assertEquals("Confirmado",db.events(true).get(0).state);assertEquals("105",db.events(true).get(0).remoteId);
        }finally{context.deleteDatabase(file);}
    }
    @Test public void upgradesPreviousQueueWithoutDroppingRecords() throws Exception {
        String file="migration-test.db";context.deleteDatabase(file);
        try(SQLiteDatabase old=context.openOrCreateDatabase(file,0,null)){
            old.execSQL("CREATE TABLE pending (id INTEGER PRIMARY KEY AUTOINCREMENT,type INTEGER NOT NULL,body TEXT NOT NULL)");
            old.execSQL("INSERT INTO pending(type,body) VALUES(2,'{}')");old.setVersion(1);
        }
        try(Outbox db=new Outbox(context,file)){assertEquals(1,db.pendingCount());assertEquals(2,db.first().type);assertTrue(db.trips().isEmpty());}
        finally{context.deleteDatabase(file);}
    }
    @Test public void tripPointsAndSmsAcknowledgementsArePersisted() throws Exception {
        String file="trip-test.db";context.deleteDatabase(file);
        try(Outbox db=new Outbox(context,file)){
            long trip=db.startTrip();Location a=location(-23.55,-46.63);db.point(trip,a,0);db.point(trip,location(-23.551,-46.631),150);
            assertEquals(2,db.points(trip).length());assertEquals(150,db.trips().get(0).distance,0.01);db.finishTrip(trip);assertTrue(db.trips().get(0).finish>0);
            long event=db.event(trip,2,"SOS","Teste","Local"),sms=db.sms(event,"Contato",2,"Aguardando operadora");
            db.smsResult(sms,true);assertTrue(db.smsSummary(event).contains("Enviando"));db.smsResult(sms,true);assertTrue(db.smsSummary(event).contains("Aceito pela operadora"));
            sms=db.sms(event,"Contato 2",2,"Aguardando operadora");db.smsResult(sms,false);db.smsResult(sms,true);assertTrue(db.smsSummary(event).contains("Falhou"));
        }finally{context.deleteDatabase(file);}
    }
    @Test public void contactsValidateAndLocationExpires() throws Exception {
        assertEquals("+5511912345678",Contacts.phone("+55 (11) 91234-5678"));assertTrue(Contacts.validPhone("11912345678"));assertFalse(Contacts.validPhone("190"));assertFalse(Contacts.validPhone("119ABC56789"));
        JSONArray list=new JSONArray("[{\"name\":\"Contato\",\"phone\":\"11912345678\"}]");assertTrue(Contacts.duplicate(list,"11912345678"));
        Location fresh=location(-23,-46);assertTrue(MonitorService.fresh(fresh));fresh.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos()-121000000000L);assertFalse(MonitorService.fresh(fresh));
    }
    @Test public void screensFormsAndNativeMapRender() throws Exception {
        MainActivity activity=launch();
        try{
            screenshot("inicio");
            main(()->activity.selectTab(1));assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("sensor0"));screenshot("sensores");
            main(()->activity.selectTab(2));screenshot("historico");
            main(()->activity.selectTab(3));assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("addContact"));screenshot("ajustes");
            main(()->((EditText)activity.findViewById(android.R.id.content).findViewWithTag("profileName")).setText("Apresentação"));
            main(()->activity.findViewById(android.R.id.content).findViewWithTag("saveName").performClick());assertEquals("Apresentação",context.getSharedPreferences("socorro",0).getString("name",""));
            Method nightMethod=MainActivity.class.getDeclaredMethod("applyNight",boolean.class);nightMethod.setAccessible(true);
            main(()->{try{context.getSharedPreferences("socorro",0).edit().putString("themeMode","dark").commit();nightMethod.invoke(activity,true);}catch(Exception e){throw new RuntimeException(e);}});
            main(()->activity.findViewById(android.R.id.content).findViewWithTag("addContact").performClick());
            Field contactDialogField=MainActivity.class.getDeclaredField("openedDialog");contactDialogField.setAccessible(true);
            AlertDialog contactDialog=(AlertDialog)contactDialogField.get(activity);
            main(()->{
                ((EditText)contactDialog.getWindow().getDecorView().findViewWithTag("contactName")).setText("Contato de teste");
                ((EditText)contactDialog.getWindow().getDecorView().findViewWithTag("contactPhone")).setText("+5511000000000");
            });
            screenshot("contato-noturno");
            main(()->contactDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            assertEquals(1,Contacts.read(context.getSharedPreferences("socorro",0)).length());
            assertFalse(context.getSharedPreferences("socorro",0).getBoolean("smsEnabled",true));
            
            JSONArray points=new JSONArray("[[-23.5505,-46.6333],[-23.5510,-46.6340],[-23.5520,-46.6340]]");
            Method show=MainActivity.class.getDeclaredMethod("showMap",JSONArray.class,String.class,String.class);show.setAccessible(true);
            main(()->{try{show.invoke(activity,points,"Mapa · teste local","Pontos de teste do mapa");}catch(Exception e){throw new RuntimeException(e);}});
            SystemClock.sleep(1500);
            Field dialogField=MainActivity.class.getDeclaredField("openedDialog");dialogField.setAccessible(true);Dialog dialog=(Dialog)dialogField.get(activity);
            RouteMapView map=dialog.getWindow().getDecorView().findViewWithTag("routeMap");assertNotNull(map);
            waitFor(()->map.isPainted()&&map.getWidth()>0&&map.getHeight()>0,5000);assertEquals(3,map.pointCount());
            screenshot("mapa");main(dialog::dismiss);
            Method theme=MainActivity.class.getDeclaredMethod("applyNight",boolean.class);theme.setAccessible(true);
            main(()->{activity.selectTab(0);try{theme.invoke(activity,true);}catch(Exception e){throw new RuntimeException(e);}});screenshot("noturno");
        }finally{main(activity::finish);}
    }
    @Test public void lightThemeChangesQuicklyAndPreservesDraft() throws Exception {
        MainActivity activity=launch();
        try{
            Field sensorField=MainActivity.class.getDeclaredField("readings");sensorField.setAccessible(true);
            Field darkField=MainActivity.class.getDeclaredField("dark");darkField.setAccessible(true);
            Field initialized=MainActivity.class.getDeclaredField("lightThemeInitialized");initialized.setAccessible(true);
            Method theme=MainActivity.class.getDeclaredMethod("updateLightTheme",long.class);theme.setAccessible(true);
            main(()->{
                try{
                    activity.selectTab(3);
                    EditText input=activity.findViewById(android.R.id.content).findViewWithTag("profileName");
                    input.setText("Nome ainda não salvo");input.requestFocus();input.setSelection(5);
                    context.getSharedPreferences("socorro",0).edit().putBoolean("autoNight",true).commit();
                    SensorReadings readings=(SensorReadings)sensorField.get(activity);
                    initialized.setBoolean(activity,false);readings.light=0;theme.invoke(activity,1000L);
                    assertTrue(darkField.getBoolean(activity));
                    input=activity.findViewById(android.R.id.content).findViewWithTag("profileName");
                    assertEquals("Nome ainda não salvo",input.getText().toString());assertTrue(input.hasFocus());assertEquals(5,input.getSelectionStart());
                    int darkColor=((android.graphics.drawable.ColorDrawable)activity.findViewById(android.R.id.content).findViewWithTag("root").getBackground()).getColor();
                    readings.light=200;theme.invoke(activity,1100L);assertFalse(darkField.getBoolean(activity));
                    int lightColor=((android.graphics.drawable.ColorDrawable)activity.findViewById(android.R.id.content).findViewWithTag("root").getBackground()).getColor();
                    assertEquals(android.graphics.Color.BLACK,darkColor);assertNotEquals(darkColor,lightColor);
                    readings.light=60;theme.invoke(activity,2000L);theme.invoke(activity,3000L);assertFalse(darkField.getBoolean(activity));
                    readings.light=10;theme.invoke(activity,3100L);theme.invoke(activity,3400L);assertTrue(darkField.getBoolean(activity));
                    context.getSharedPreferences("socorro",0).edit().putBoolean("autoNight",false).commit();
                    readings.light=200;theme.invoke(activity,4000L);theme.invoke(activity,5000L);assertEquals((activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES,darkField.getBoolean(activity));
                }catch(Exception e){throw new RuntimeException(e);}
            });
        }finally{main(activity::finish);}
    }
    @Test public void automaticThemeRespondsToSensorEvents() throws Exception {
        context.getSharedPreferences("socorro",0).edit().putString("themeMode","auto").putBoolean("autoNight",true).putInt("themeThreshold",60).commit();
        MainActivity activity=launch();
        try{
            Field readingsField=MainActivity.class.getDeclaredField("readings");readingsField.setAccessible(true);
            Field darkField=MainActivity.class.getDeclaredField("dark");darkField.setAccessible(true);
            SensorReadings readings=(SensorReadings)readingsField.get(activity);
            waitFor(()->Float.isFinite(readings.light)&&readings.light<10&&darkField.getBoolean(activity),15000);
            main(()->assertEquals(android.graphics.Color.BLACK,((android.graphics.drawable.ColorDrawable)activity.findViewById(android.R.id.content).findViewWithTag("root").getBackground()).getColor()));
            screenshot("luminosidade-escuro");android.util.Log.i("ThemeSensorTest","WAIT_HIGH");
            waitFor(()->readings.light>=200&&!darkField.getBoolean(activity),25000);
            main(()->assertNotEquals(android.graphics.Color.BLACK,((android.graphics.drawable.ColorDrawable)activity.findViewById(android.R.id.content).findViewWithTag("root").getBackground()).getColor()));
            screenshot("luminosidade-claro");
        }finally{main(activity::finish);}
    }
    @Test public void themePickerSeparatesAutomaticLightDarkAndSystem() throws Exception {
        MainActivity activity=launch();
        try{
            main(()->{activity.selectTab(3);((Spinner)activity.findViewById(android.R.id.content).findViewWithTag("themeMode")).setSelection(2);});
            assertEquals("dark",context.getSharedPreferences("socorro",0).getString("themeMode",""));
            main(()->assertEquals(android.graphics.Color.BLACK,((android.graphics.drawable.ColorDrawable)activity.findViewById(android.R.id.content).findViewWithTag("root").getBackground()).getColor()));
            main(()->((Spinner)activity.findViewById(android.R.id.content).findViewWithTag("themeMode")).setSelection(1));
            assertEquals("light",context.getSharedPreferences("socorro",0).getString("themeMode",""));
            main(()->assertNotEquals(android.graphics.Color.BLACK,((android.graphics.drawable.ColorDrawable)activity.findViewById(android.R.id.content).findViewWithTag("root").getBackground()).getColor()));
            main(()->((Spinner)activity.findViewById(android.R.id.content).findViewWithTag("themeMode")).setSelection(0));
            assertEquals("auto",context.getSharedPreferences("socorro",0).getString("themeMode",""));
            main(()->((Spinner)activity.findViewById(android.R.id.content).findViewWithTag("themeMode")).setSelection(3));
            assertEquals("system",context.getSharedPreferences("socorro",0).getString("themeMode",""));
        }finally{main(activity::finish);}
    }
    @Test public void liveEndpointStatusForManualDiagnosis() throws Exception {
        org.junit.Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("liveApi")));
        JSONObject request=new JSONObject().put("usuario","Teste de integracao Socorro Android").put("tipo_registro",1).put("device_id","socorro-android-teste-"+java.util.UUID.randomUUID()).put("modelo","Emulador de teste de integracao");
        Method http=ApiClient.class.getDeclaredMethod("http",String.class,String.class,String.class);http.setAccessible(true);
        try{JSONObject response=(JSONObject)http.invoke(null,"POST",ApiClient.ENDPOINT,request.toString());ApiClient.validateConfirmation(response,request);android.util.Log.i("SocorroApiLive","CONFIRMADO: registro "+response.optString("id")+"; nome: "+response.optString("usuario"));}
        catch(InvocationTargetException error){Throwable cause=error.getCause();android.util.Log.i("SocorroApiLive","SEM_CONFIRMACAO: "+ApiClient.errorMessage(cause instanceof Exception?(Exception)cause:error));}
        catch(Exception error){android.util.Log.i("SocorroApiLive","SEM_CONFIRMACAO: "+ApiClient.errorMessage(error));}
    }
    @Test public void apiRejectsHtmlAndPreservesOriginalSuccessCompatibility() throws Exception {
        JSONObject request=new JSONObject("{\"usuario\":\"Enzo teste\",\"tipo_registro\":1}");
        try{ApiClient.parseResponse(405,"<html>405 Not Allowed</html>");fail("HTTP 405 não pode confirmar envio");}catch(IOException error){assertTrue(error.getMessage().contains("405"));}
        try{ApiClient.parseResponse(200,"<html>captcha</html>");fail("Captcha não é resposta da API");}catch(IOException error){assertTrue(error.getMessage().contains("segurança"));}
        ApiClient.validateConfirmation(new JSONObject("{\"status\":\"sucesso\"}"),request);
        JSONObject normalized=new JSONObject("{\"status\":\"sucesso\",\"id\":12,\"usuario\":\"Outro nome\"}");ApiClient.validateConfirmation(normalized,request);assertFalse(ApiClient.confirmationWarning(normalized,request).isEmpty());
        ApiClient.validateConfirmation(ApiClient.parseResponse(200,"{\"status\":\"sucesso\",\"id\":12,\"usuario\":\"Enzo teste\",\"tipo_registro\":1}"),request);
        assertTrue(ApiClient.errorMessage(new javax.net.ssl.SSLHandshakeException("handshake")).contains("HTTPS"));
    }
    @Test public void payloadKeepsSavedNameAndOnlyAvailableSensorData() throws Exception {
        context.getSharedPreferences("socorro",0).edit().putString("name","  Enzo integração  ").commit();
        SensorReadings readings=new SensorReadings((android.hardware.SensorManager)context.getSystemService(Context.SENSOR_SERVICE));
        JSONObject noGps=TelemetryPayload.create(context,null,readings,1,0);assertEquals("Enzo integração",noGps.getString("usuario"));assertFalse(noGps.has("latitude"));assertFalse(noGps.has("pressao"));assertFalse(noGps.has("temperatura"));assertTrue(noGps.has("bateria"));
        Location location=location(-23.55,-46.63);location.setSpeed(5);readings.acceptPressure(1013.25f,1000000000L);readings.temperature=24;
        JSONObject withGps=TelemetryPayload.create(context,location,readings,2,3.5);assertEquals(noGps.getString("device_id"),withGps.getString("device_id"));assertEquals(18,withGps.getDouble("velocidade"),0.001);assertEquals(1013.25,withGps.getDouble("pressao"),0.01);assertEquals(24,withGps.getDouble("temperatura"),0.001);assertEquals(3.5,withGps.getDouble("nivel_queda"),0.001);
    }
    @Test public void successWithoutIdDoesNotRemainPendingOrGetResent() throws Exception {
        waitFor(()->!SyncManager.busy,5000);try(Outbox db=new Outbox(context)){db.getWritableDatabase().delete("pending",null,null);db.add(1,"{\"usuario\":\"Nome original\",\"tipo_registro\":1}",0,"Registro","Teste");}
        AtomicInteger calls=new AtomicInteger();ApiClient.transport=(method,url,body)->{calls.incrementAndGet();return new JSONObject("{\"status\":\"sucesso\"}");};
        SyncManager.flush(context,null);waitFor(()->!SyncManager.busy,8000);
        try(Outbox db=new Outbox(context)){assertEquals(0,db.pendingCount());assertEquals("Confirmado",db.events(false).get(0).state);assertEquals("Nome original",new JSONObject(db.events(false).get(0).payload).getString("usuario"));}
        SyncManager.flush(context,null);waitFor(()->!SyncManager.busy,8000);assertEquals(1,calls.get());
    }
    @Test public void upgradeV2PreservesPayloadAfterAcknowledgement() throws Exception {
        String file="payload-migration-test.db";context.deleteDatabase(file);
        try(SQLiteDatabase old=context.openOrCreateDatabase(file,0,null)){
            old.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY,at INTEGER,trip INTEGER,title TEXT,detail TEXT,kind INTEGER,state TEXT,remote_id TEXT)");old.execSQL("CREATE TABLE pending(id INTEGER PRIMARY KEY,type INTEGER,body TEXT,event_id INTEGER)");
            old.execSQL("INSERT INTO events VALUES(1,0,0,'Registro','',1,'Pendente',NULL)");old.execSQL("INSERT INTO pending VALUES(1,1,?,1)",new Object[]{"{\"usuario\":\"Nome da versão anterior\",\"tipo_registro\":1}"});old.setVersion(2);
        }
        try(Outbox db=new Outbox(context,file)){Outbox.Entry entry=db.first();db.confirmed(entry,"703");assertEquals(0,db.pendingCount());assertEquals("Nome da versão anterior",new JSONObject(db.events(false).get(0).payload).getString("usuario"));}finally{context.deleteDatabase(file);}
    }
    @Test public void proximityRequiresRealValidReadingAndActiveListener() {
        SensorReadings readings=new SensorReadings((android.hardware.SensorManager)context.getSystemService(Context.SENSOR_SERVICE));
        readings.available[2]=true;readings.listening[2]=true;assertFalse(readings.hasProximityReading());
        readings.acceptProximity(0,5);assertTrue(readings.hasProximityReading());assertTrue(readings.covered);
        readings.acceptProximity(5,5);assertTrue(readings.hasProximityReading());assertFalse(readings.covered);
        readings.acceptProximity(0,1);assertTrue(readings.covered);readings.acceptProximity(1,1);assertFalse(readings.covered);
        readings.acceptProximity(Float.NaN,5);assertFalse(readings.hasProximityReading());
        readings.acceptProximity(-1,5);assertFalse(readings.hasProximityReading());
        readings.acceptProximity(0,0);assertFalse(readings.hasProximityReading());
        readings.acceptProximity(0,Float.NaN);assertFalse(readings.hasProximityReading());
        readings.acceptProximity(0,5);readings.listening[2]=false;assertFalse(readings.hasProximityReading());
        readings.listening[2]=true;readings.available[2]=false;assertFalse(readings.hasProximityReading());
    }
    @Test public void monitoringAndAlertContinueWithScreenOffAndStopFromNotification() throws Exception {
        MainActivity activity=launch();
        try{
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_COARSE_LOCATION);
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_FINE_LOCATION);
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
            context.getSharedPreferences("socorro",0).edit().putBoolean("notificationAsked",true).putInt("checkInMinutes",30).commit();
            main(()->activity.findViewById(android.R.id.content).findViewWithTag("monitor").performClick());
            waitFor(()->MonitorService.running&&MonitorService.lastTickAt>0&&MonitorService.lastAccelerationAt>0,8000);
            SensorReadings serviceReadings=MonitorService.currentReadings;assertNotNull(serviceReadings);assertTrue(serviceReadings.listening[0]);
            main(()->activity.moveTaskToBack(true));
            shell("input keyevent 223");
            waitFor(()->!context.getSystemService(PowerManager.class).isInteractive(),5000);
            long previous=MonitorService.lastTickAt;SystemClock.sleep(1800);
            assertTrue(MonitorService.running);assertTrue(MonitorService.lastTickAt>previous);
            assertTrue(SystemClock.elapsedRealtime()-MonitorService.lastAccelerationAt<5000);
            MonitorService.nextCheckIn=SystemClock.elapsedRealtime()+200;
            waitFor(()->MonitorService.alertReason==SafetyTimer.CHECK_IN,5000);
            waitFor(()->MonitorService.alertDeadline==0,34000);
            try(Outbox db=new Outbox(context)){
                waitFor(()->!db.events(true).isEmpty()&&db.events(true).get(0).state.equals("Confirmado"),5000);
                assertEquals("Check-in sem resposta",db.events(true).get(0).title);
            }
            NotificationManager notifications=context.getSystemService(NotificationManager.class);
            PendingIntent stop=null;
            for(android.service.notification.StatusBarNotification posted:notifications.getActiveNotifications()){
                if(posted.getId()==1&&posted.getNotification().actions!=null)stop=posted.getNotification().actions[0].actionIntent;
            }
            assertNotNull(stop);stop.send();waitFor(()->!MonitorService.running,5000);assertNull(MonitorService.currentReadings);
            long stopped=MonitorService.lastTickAt;SystemClock.sleep(600);assertEquals(stopped,MonitorService.lastTickAt);
        }finally{shell("input keyevent 224");shell("wm dismiss-keyguard");main(activity::finish);}
    }
    private void shell(String command) throws Exception {
        try(InputStream result=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command))){byte[] data=new byte[1024];while(result.read(data)!=-1){}}
    }
    @Test public void barometerProcessesPressureAndRejectsInvalidReadings() {
        SensorReadings readings=new SensorReadings((android.hardware.SensorManager)context.getSystemService(Context.SENSOR_SERVICE));
        assertTrue(Float.isNaN(readings.pressure));
        readings.acceptPressure(1013.25f,1000000000L);assertEquals(1013.25f,readings.pressure,0.001);assertEquals(0,readings.altitudeDrop,0.001);
        readings.acceptPressure(1013.45f,2000000000L);assertTrue(readings.altitudeDrop>1);
        readings.acceptPressure(1013.05f,2500000000L);assertTrue(readings.altitudeDrop< -1);
        float last=readings.pressure;readings.acceptPressure(Float.NaN,2600000000L);readings.acceptPressure(0,2700000000L);readings.acceptPressure(-1,2800000000L);readings.acceptPressure(Float.POSITIVE_INFINITY,2900000000L);assertEquals(last,readings.pressure,0);
        readings.acceptPressure(1014,5000000001L);assertEquals(0,readings.altitudeDrop,0.001);
    }
    @Test public void whatsappQueuesContactsWithoutSendingSms() throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("socorro",0);
        assertTrue(AlertChannels.usesSms(prefs));
        prefs.edit().putString("alertChannel","whatsapp").putString("contacts","[{\"name\":\"Contato\",\"phone\":\"11900000000\"}]").commit();
        assertFalse(AlertChannels.usesSms(prefs));
        String file="channels-test.db";context.deleteDatabase(file);
        try(Outbox db=new Outbox(context,file)){
            long event=db.event(0,2,"SOS","Teste","Local");
            JSONObject body=new JSONObject().put("usuario","Teste").put("latitude",-23.5).put("longitude",-46.6);
            AlertChannels.send(context,db,event,body,"SOS manual");
            assertEquals("",db.smsSummary(event));
            JSONArray queue=AlertChannels.pending(prefs);assertEquals(1,queue.length());
            assertEquals(1,queue.getJSONObject(0).getJSONArray("contacts").length());
            String message=queue.getJSONObject(0).getString("message");assertTrue(message.contains("SOS manual"));assertTrue(message.contains("https://maps.google.com/"));
            android.net.Uri uri=AlertChannels.whatsAppUri("(11) 90000-0000",message);
            assertEquals("/5511900000000",uri.getPath());assertEquals(message,uri.getQueryParameter("text"));
            assertEquals("/351912345678",AlertChannels.whatsAppUri("+351912345678",message).getPath());
            prefs.edit().putString("alertChannel","both").commit();assertTrue(AlertChannels.usesSms(prefs));
            prefs.edit().putString("alertChannel","sms").commit();AlertChannels.clear(context);assertEquals(0,AlertChannels.pending(prefs).length());
        }finally{AlertChannels.clear(context);context.deleteDatabase(file);}
    }
    @Test public void sosCancelsAndCheckInExpiresWithMockedHttp() throws Exception {
        MainActivity activity=launch();
        try{
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_COARSE_LOCATION);
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_FINE_LOCATION);
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
            context.getSharedPreferences("socorro",0).edit().putBoolean("notificationAsked",true).apply();
            main(()->activity.findViewById(android.R.id.content).findViewWithTag("sos").performClick());
            waitFor(()->MonitorService.alertDeadline>0,8000);screenshot("alerta");
            main(()->activity.findViewById(android.R.id.content).findViewWithTag("cancelAlert").performClick());waitFor(()->MonitorService.alertDeadline==0,3000);
            MonitorService.nextCheckIn=SystemClock.elapsedRealtime()+200;
            waitFor(()->MonitorService.alertReason==SafetyTimer.CHECK_IN,4000);
            waitFor(()->MonitorService.alertDeadline==0,34000);
            try(Outbox db=new Outbox(context)){
                waitFor(()->!db.events(true).isEmpty()&&db.events(true).get(0).state.equals("Confirmado"),5000);
                Outbox.Event event=db.events(true).get(0);assertEquals("Check-in sem resposta",event.title);assertEquals("42",event.remoteId);
                assertTrue(db.smsSummary(event.id).contains("Nenhum contato cadastrado"));
            }
        }finally{main(activity::finish);}
    }
    private MainActivity launch(){return (MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private void main(Runnable action){instrumentation.runOnMainSync(action);instrumentation.waitForIdleSync();}
    private interface Condition{boolean get()throws Exception;}
    private void waitFor(Condition condition,long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(!condition.get()){if(SystemClock.elapsedRealtime()>end)fail("Tempo excedido");SystemClock.sleep(100);}}
    private Location location(double lat,double lon){Location location=new Location("gps");location.setLatitude(lat);location.setLongitude(lon);location.setTime(System.currentTimeMillis());location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());location.setAccuracy(5);return location;}
    private void screenshot(String name)throws Exception{
        instrumentation.waitForIdleSync();SystemClock.sleep(300);Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File dir=new File(context.getExternalFilesDir(null),"screens");assertTrue(dir.isDirectory()||dir.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
}

