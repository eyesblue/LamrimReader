package eyes.blue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import com.crashlytics.android.Crashlytics;
import eyes.blue.RemoteDataSource.RemoteSource;


public class DownloadAllService extends IntentService {

	public static final String NOTIFICATION = "eyes.blue.action.DownloadAllService";
	SharedPreferences runtime = null;
	int defaultThreads = 4, downloadTimes=0,retryTimes=3;
	Downloader threadPool[] = null;
	String notifyMsg[]=null, logTag=getClass().getName();
	Integer downloadIndex = 0;
	Integer successCount=0;
	Integer failureCount=0;
	public static int notificationId=0;	// Always update notification but create new one.
	FileSysManager fsm= null;
	boolean cancelled=false, hasFailure=false;
	PowerManager powerManager=null;
	WakeLock wakeLock = null;
	
	public DownloadAllService() {
		super("DownloadAllService");
	}
	public DownloadAllService(String name) {
		super(name);
	}
	
	@Override
    public void onDestroy() {
		Crashlytics.log(Log.DEBUG,getClass().getName(), "Stop download all service");
		cancelled=true;
		removeNotification();
    }

	@Override
	protected void onHandleIntent(Intent intent) {
		Crashlytics.log(Log.DEBUG, getClass().getName(), "Into onHandleIntent of download all service");

		fsm=new FileSysManager(getBaseContext());
		defaultThreads=intent.getIntExtra("threadCount", 4);
		if(defaultThreads<1){
			Crashlytics.log(Log.DEBUG,getClass().getName(), "DownloadAllService receive incorrect thread count "+defaultThreads+", skip service.");
			return;
		}
		threadPool = new Downloader[defaultThreads];
		notifyMsg=new String[defaultThreads];
		powerManager=(PowerManager) getSystemService(Context.POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
		
		for(int i=0;i<defaultThreads;i++){
			notifyMsg[i]="啟動中";
		}
		
		boolean isAlive=false;
		for(int i=0;i<defaultThreads;i++){
			if(threadPool[i] != null && threadPool[i].isAlive())
				isAlive=true;
		}
		
		if(isAlive){
			Crashlytics.log(Log.DEBUG,getClass().getName(), "Task downloading, skip the start command.");
			return;
		}
		
		runtime = getSharedPreferences(getString(R.string.runtimeStateFile), 0);
		
/*		if(downloader!=null && downloader.getStatus()==AsyncTask.Status.RUNNING){
			Crashlytics.log(Log.DEBUG,getClass().getName(), "Task downloading, skip the start command.");
			return;
		}
*/		
		if(!wakeLock.isHeld()){wakeLock.acquire();}
		Crashlytics.log(Log.DEBUG,getClass().getName(), "Start download all service");
		for(int i=0;i<defaultThreads;i++){
			threadPool[i]=new Downloader(i);
			threadPool[i].start();
		}
		
		// Send a broadcast to notify receiver that service has start
		reportServiceStart();
		
		while (true) {
			Crashlytics.log(Log.DEBUG,getClass().getName(),	"Main thread of service wake up, download index=" + downloadIndex);
			boolean alive = false;
			synchronized (threadPool) {
				for (int i = 0; i < defaultThreads; i++)
					if (threadPool[i].isAlive())
						alive = true;

				if (!alive) { // In to the scope while worker threads terminate.
					if (hasFailure) { // Some download task failure.
						downloadIndex = 0;
						hasFailure = false;
						if(++downloadTimes>=retryTimes){
							if(wakeLock.isHeld())wakeLock.release();
							reportDownloadAllTerminate();
							break;
						}
						for (int j = 0; j < defaultThreads; j++) {
							threadPool[j] = new Downloader(j);
							threadPool[j].start();
						}
					} else { // Download all finish and no failure.
						reportDownloadAllTerminate();
						if(wakeLock.isHeld())wakeLock.release();
						break;
					}
				}

/*				if (cancelled) {
					reportDownloadAllTerminate();
					removeNotification();
					break;
				}
				*/
				try {
					threadPool.wait(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} // synchroinzed
		} // while
		Crashlytics.log(Log.DEBUG,getClass().getName(),"Download All Service terminate.");
	}
	

	private void reportServiceStart(){
		Intent bcIntent = new Intent();
		bcIntent.setAction(NOTIFICATION);
		bcIntent.putExtra("action", "start");
		sendBroadcast(bcIntent); 
		
		notifyMsg("LamrimReader Downloader All Service", "Service started.");
	}
	
	private void reportDownloadAllTerminate(){
		Intent bcIntent = new Intent();
		bcIntent.setAction(NOTIFICATION);
		bcIntent.putExtra("action", "terminate");
//		bcIntent.putExtra("cause", "finish");
		sendBroadcast(bcIntent); 
		
		removeNotification();
	}
	
	private void reportStorageUnusable(){
		Intent bcIntent = new Intent();
		bcIntent.setAction(NOTIFICATION);
		bcIntent.putExtra("action", "error");
		bcIntent.putExtra("desc", getString(R.string.errStorageNotReady));
		sendBroadcast(bcIntent); 
		
		removeNotification();
	}
	
/*	private void publishResults(int index) {
	    Intent intent = new Intent(NOTIFICATION);
	    intent.putExtra("action", "download");
	    intent.putExtra("index", index);
	    
	    sendBroadcast(intent);
	}
*/	
	/*
	 * This function just need for notification, but not SpeechMenuActivity.
	 * */
	private synchronized void reportStartDownloadIndex(int threadId, int downloadIndex) {
		if(threadId == 0)notifyMsg[threadId]=SpeechData.getSubtitleName(downloadIndex);
		else notifyMsg[threadId]=", "+SpeechData.getSubtitleName(downloadIndex);
		String msg=getString(R.string.msgDownloadList);
		
		for(int i=0;i<defaultThreads;i++){
			msg+=notifyMsg[i];
		}
		notifyMsg(getString(R.string.msgLamrimDownloadList), msg);
	}
	
	/*
	 * Download state need for SpeeechMenuActivity, but not notification bar. 
	 * */
	private void reportDownloadState(int threadId, int downloadIndex, boolean isSuccess) {
		Intent bcIntent = new Intent();
		bcIntent.setAction(NOTIFICATION);
		bcIntent.putExtra("action", "download");
		bcIntent.putExtra("index", downloadIndex);
		bcIntent.putExtra("isSuccess", isSuccess);
		sendBroadcast(bcIntent);
	}
	
	private void notifyMsg(String title, String contentText) {
		ApiLevelAdaptor.notifyMsg(DownloadAllService.this, title, contentText);
		/*
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
		        .setSmallIcon(R.drawable.ic_launcher)
		        .setContentTitle(title)
		        .setContentText(contentText);
		// Creates an explicit intent for an Activity in your app
		ComponentName c=new ComponentName(DownloadAllService.this, DownloadAllServiceHandler.class);
		Intent intent = new Intent();
		intent.setComponent(c);
		
		// Sets the Activity to start in a new, empty task
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		// Creates the PendingIntent
		PendingIntent notifyIntent =
		        PendingIntent.getActivity(
		        this,
		        0,
		        intent,
		        PendingIntent.FLAG_UPDATE_CURRENT
		);


		// Puts the PendingIntent into the notification builder
		builder.setContentIntent(notifyIntent);
		// Notifications are issued by sending them to the
		// NotificationManager system service.
		NotificationManager mNotificationManager =
		    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		// mId allows you to update the notification later on.
		mNotificationManager.notify(notificationId, builder.build());
		*/
	  }
	
	private void removeNotification(){
		ApiLevelAdaptor.removeNotification(DownloadAllService.this);
		//NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		//mNotificationManager.cancel(notificationId);
	}

	public class Downloader extends Thread{
		int tId = -1;
//		boolean cancelled=false;
		
		public Downloader(int id){
			this.tId=id;
		}
		
/*		public void stopRun(){
			this.cancelled=true;
		}
*/		
		
		@Override
		public void run(){
			try{
				runTask();
			}catch(Exception e){
				e.printStackTrace();
			}
		}
		
		private void runTask(){
			
	    	String locale = DownloadAllService.this.getResources().getConfiguration().locale.getCountry();
	    	RemoteSource rs = Util.getRemoteSource(DownloadAllService.this)[0];

	    	int downloadingIndex=-1;
	    	while(downloadIndex<SpeechData.name.length){
	    		boolean isDwSuccess=false;

	    		synchronized(threadPool){
	    			if(downloadIndex>=SpeechData.name.length){
	    				Crashlytics.log(Log.DEBUG,"DownloadAllThread","Thread"+tId+" Terminate, End of media index reached.");
	    				return ;
	    			}
	    		
	    			downloadingIndex=downloadIndex;
	    			downloadIndex++;
	    			threadPool.notifyAll();
	    		}
	    		Crashlytics.log(Log.DEBUG,"DownloadAllThread","Thread"+tId+" get download task index "+downloadIndex);
	    		
	    		
	    		if(cancelled){
	    			Crashlytics.log(Log.DEBUG,getClass().getName(),"Thread_"+tId+" Terminate, Task has canceled.");
	    			return;
	    		}

				File mediaFile=fsm.getLocalMediaFile(downloadingIndex);
				if(mediaFile==null){
					Crashlytics.log(Log.DEBUG,getClass().getName(),"The storage media has not usable, skip.");
					reportStorageUnusable();
					return;
				}

				if(!mediaFile.exists()){
					reportStartDownloadIndex(tId, downloadingIndex);
					isDwSuccess=download(rs.getMediaFileAddress(downloadingIndex), mediaFile.getAbsolutePath());
					if(!isDwSuccess)
						hasFailure=true;
				}
				reportDownloadState(notificationId, downloadingIndex, isDwSuccess);
			}
			reportDownloadAllTerminate();
		}
		
		public boolean download(String url, String outputPath){
	        Crashlytics.log(Log.DEBUG,getClass().getName(),"Download file from "+url);
	        File tmpFile=new File(outputPath+getString(R.string.downloadTmpPostfix));
	        long startTime=System.currentTimeMillis(), respWaitStartTime;

	        int readLen=-1, counter=0, bufLen=getResources().getInteger(R.integer.downloadBufferSize);
	        FileOutputStream fos=null;

	        //HttpClient httpclient = getNewHttpClient();
	        HttpClient httpclient = new DefaultHttpClient();
	        HttpGet httpget = new HttpGet(url);
	        HttpResponse response=null;
	        int respCode=-1;
	        if(cancelled){Crashlytics.log(Log.DEBUG,getClass().getName(),"User canceled, download procedure skip!");return false;}
	       
//	        setProgressMsg(activity.getString(R.string.dlgTitleConnecting),String.format(activity.getString(R.string.dlgDescConnecting), SpeechData.getNameId(mediaIndex),(type == activity.getResources().getInteger(R.integer.MEDIA_TYPE))?"音檔":"字幕"));
	       
	        try {
	        	respWaitStartTime=System.currentTimeMillis();
	        	response = httpclient.execute(httpget);
	        	respCode=response.getStatusLine().getStatusCode();

	        	// For debug
	        	if(respCode!=HttpStatus.SC_OK){
	        		httpclient.getConnectionManager().shutdown();
	        		System.out.println("CheckRemoteThread: Return code not equal 200! check return "+respCode);
					return false;
	        	}
	        }catch (ClientProtocolException e) {
	        	httpclient.getConnectionManager().shutdown();
	        	e.printStackTrace();
	        	return false;
	        }catch (Exception e) {
	        	httpclient.getConnectionManager().shutdown();
	        	e.printStackTrace();
	        	return false;
	        }

	        if(cancelled){
	        	httpclient.getConnectionManager().shutdown();
	        	Crashlytics.log(Log.DEBUG,getClass().getName(),"User canceled, download procedure skip!");
	        	return false;
	        }
			Crashlytics.setDouble("ResponseTimeOfDownload", (System.currentTimeMillis()-respWaitStartTime));

			HttpEntity httpEntity=response.getEntity();
	        InputStream is=null;
	        try {
	                is = httpEntity.getContent();
	        } catch (IllegalStateException e2) {
	                try {   is.close();     } catch (IOException e) {e.printStackTrace();}
	                httpclient.getConnectionManager().shutdown();
	                tmpFile.delete();
	                e2.printStackTrace();
	                return false;
	        } catch (IOException e2) {
	                httpclient.getConnectionManager().shutdown();
	                tmpFile.delete();
	                e2.printStackTrace();
	                return false;
	        }
	       
	        if(cancelled){
	                Crashlytics.log(Log.DEBUG,getClass().getName(),"User canceled, download procedure skip!");
	                try {   is.close();     } catch (IOException e) {e.printStackTrace();}
	                httpclient.getConnectionManager().shutdown();
	                tmpFile.delete();
	                return false;
	        }
	       
	        final long contentLength=httpEntity.getContentLength();

	        try {
	                fos=new FileOutputStream(tmpFile);
	        } catch (FileNotFoundException e1) {
	                Crashlytics.log(Log.DEBUG,getClass().getName(),"File Not Found Exception happen while create output temp file ["+tmpFile.getName()+"] !");
	                httpclient.getConnectionManager().shutdown();
	                try {   is.close();     } catch (IOException e) {e.printStackTrace();}
	                tmpFile.delete();
	                e1.printStackTrace();
	                return false;
	        }

	        if(cancelled){
	        	httpclient.getConnectionManager().shutdown();
	        	try {   is.close();     } catch (IOException e) {e.printStackTrace();}
	        	try {   fos.close();    } catch (IOException e) {e.printStackTrace();}
	        	tmpFile.delete();
	        	Crashlytics.log(Log.DEBUG,getClass().getName(),"User canceled, download procedure skip!");
	        	return false;
	        }

	        try {
		
	        	byte[] buf=new byte[bufLen];
	        	Crashlytics.log(Log.DEBUG,getClass().getName(),Thread.currentThread().getName()+": Start read stream from remote site, is="+((is==null)?"NULL":"exist")+", buf="+((buf==null)?"NULL":"exist"));
	        	while((readLen=is.read(buf))!=-1){
	        		counter+=readLen;
	        		fos.write(buf,0,readLen);

	                if(cancelled){
	                	httpclient.getConnectionManager().shutdown();
	                	try {   is.close();     } catch (IOException e) {e.printStackTrace();}
	                	try {   fos.close();    } catch (IOException e) {e.printStackTrace();}
	                	tmpFile.delete();
	                	Crashlytics.log(Log.DEBUG,getClass().getName(),"User canceled, download procedure skip!");
	                	return false;
	                }
	        	}
			is.close();
			fos.flush();
			fos.close();
	        } catch (IOException e) {
	        	httpclient.getConnectionManager().shutdown();
	        	try {   is.close();     } catch (IOException e2) {e2.printStackTrace();}
	        	try {   fos.close();    } catch (IOException e2) {e2.printStackTrace();}
	        	tmpFile.delete();
	        	e.printStackTrace();
	        	Crashlytics.log(Log.DEBUG,getClass().getName(),Thread.currentThread().getName()+": IOException happen while download media.");
	        	return false;
	        }

	        if(counter!=contentLength || cancelled){
	        	httpclient.getConnectionManager().shutdown();
	        	tmpFile.delete();
	        	return false;
	        }

	        int spend=(int) (System.currentTimeMillis()-startTime);
			Crashlytics.setDouble("SpendTimeOfDownload", (System.currentTimeMillis()-respWaitStartTime));

	        // rename the protected file name to correct file name
	        tmpFile.renameTo(new File(outputPath));
	        httpclient.getConnectionManager().shutdown();
	        Crashlytics.log(Log.DEBUG,getClass().getName(),Thread.currentThread().getName()+": Download finish, return true.");
	        return true;
		}
	}
}
