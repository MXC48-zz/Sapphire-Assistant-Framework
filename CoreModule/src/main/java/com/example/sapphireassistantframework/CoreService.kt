package com.example.sapphireassistantframework

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.componentframework.SapphireCoreService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.Exception
import java.util.*

class CoreService: SapphireCoreService() {
	val LOCAL_VERSION = "0.2.0"

	// The bound connection. The core attaches to each service as a client, tying them to cores lifecycle
	inner class Connection() : ServiceConnection {
		override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
			Log.i("Service connected")
			if (service != null) {
				// This will be moved to a permission style check, to increase user control and prevent rouge background services
				Toast.makeText(applicationContext, "This service: ${name?.shortClassName} didn't return a null binder, is that ok?", Toast.LENGTH_LONG)
			}
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			Log.i("Service disconnected")
		}
	}

	//State variables
	var initialized = false

	// This should probably be looked at more
	private var connections: LinkedList<Pair<String, Connection>> = LinkedList()

	// and this. Though this is kind of a 'fake' connection
	var connection = Connection()
	private lateinit var notificationManager: NotificationManager
	private val CHANNEL_ID = "SAF"
	private val NAME = "Sapphire Assistant Framework"
	private val SERVICE_TEXT = "Sapphire Assistant Framework"

	val CONFIG = "core.conf"
	val CONFIG_VAL_DATA_TABLES = "datatables.tbl"
	val DEFAULT_MODULES_TABLE = "defaultmodules.tbl"
	val BACKGROUND_TABLE = "background.tbl"
	val ROUTE_TABLE = "routetable.tbl"
	val ALIAS_TABLE = "alias.tbl"
	// This holds the available modules. It's close to a registry, and I hate everything about it
	var pendingIntentLedger = mutableMapOf<String,PendingIntent>()

	override fun onCreate() {
		super.onCreate()
		buildForegroundNotification()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (validateIntent(intent)) {
			var passedIntent = cleanRoute(intent!!)
			sortMail(passedIntent)
		} else {
			Log.i("There was an issue with an incoming intent. Did it say where it was FROM?")
		}
		// This may need to be moved, if I am to do things in the background
		return super.onStartCommand(intent, flags, startId)
	}

	// Check if it exists, and has the minimum information needed to go further
	fun validateIntent(intent: Intent?): Boolean {
		Log.i("Validating intent")
		when {
			intent?.action == ACTION_SAPPHIRE_INITIALIZE -> return true
			intent?.action == ACTION_SAPPHIRE_MODULE_REGISTER -> return true
			intent?.action == ACTION_SAPPHIRE_CORE_REGISTRATION_COMPLETE -> return true
			intent?.action == ACTION_REQUEST_FILE_DATA -> return true
			// I think this might now work easy for requesting modules vs installing modules
			intent?.action == ACTION_MANIPULATE_FILE_DATA -> return true
			intent?.action == "FILE_TRANSFER_FINISHED" -> return true
			intent?.hasExtra(FROM) == true -> return true
			intent?.hasExtra(ROUTE) == true -> return true
			else -> return false
		}
	}

	fun buildForegroundNotification() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val importance = NotificationManager.IMPORTANCE_HIGH
			val channel = NotificationChannel(CHANNEL_ID, NAME, importance).apply {
				description = SERVICE_TEXT
			}

			notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			notificationManager.createNotificationChannel(channel)
		}

		var notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.assistant)
			.setContentTitle("Sapphire Assistant")
			.setContentText("Thank you for trying out the Sapphire Framework")
			.setOngoing(true)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.build()

		startForeground(1337, notification)
	}

	// What is the nervous systems function called
	fun sortMail(intent: Intent) {
		Log.i("Sorting intent")
		// Looking for a better mental abstraction. These actions are more akin to heartbeats, digestion, etc. Autonomous actions, but unchangeable
		// Handle actions here
		when (initialized) {
			true -> when (intent.action) {
				ACTION_SAPPHIRE_CORE_BIND -> onBind(intent)
				// This is a change, since it doesn't make sense for Handle Route
				ACTION_REQUEST_FILE_DATA -> fileService(intent)
				ACTION_MANIPULATE_FILE_DATA -> fileService(intent)
				// Generic action
				else -> handleRoute(intent)
			}
			false -> when (intent.action) {
				ACTION_SAPPHIRE_INITIALIZE -> startRegistrationService()
				ACTION_SAPPHIRE_CORE_REGISTRATION_COMPLETE -> initialize(intent)
				ACTION_SAPPHIRE_MODULE_REGISTER -> forwardRegistration(intent)
			}
		}
	}

	fun fileService(intent: Intent?) {
		when (intent!!.action) {
			// How to tell if this is install, or request
			ACTION_REQUEST_FILE_DATA -> newCheckForLocal(intent)
			// The name may need to be changed... Request transfer gets forwarded to the module...
			ACTION_MANIPULATE_FILE_DATA -> requestTransfer(intent)
			"ACTION_BRIDGE_URI" -> Log.i("NOT YET IMPLEMENTED")
		}
	}

	fun newCheckForLocal(intent: Intent){
		// Request update from new modules. Core only needs to do the initail install one by one, because it can't start w/o everything installed
		//UpdateNewModuleData -> multiprocessModule
		val FILENAME_TABLE = "filenames.tbl"
		Log.i("Checking for local files")
		try{
			// I don't like this naming scheme, but I am hacking this together
			var fileExtensions = intent.getStringArrayListExtra(DATA_KEYS)!!
			var moduleFileRegistries = loadTable(FILENAME_TABLE)
			Log.v("These are the files in the registry that we are checking for: ${moduleFileRegistries.toString()}")
			// This is for the custom multiprocess
			var fileRequestDestinationModules = mutableListOf<String>()
			// Is there a reason I am making this blank?
			var outgoingIntent = intent
			// Oh wait. Where is this in the process?
			outgoingIntent.setClassName(this,"com.example.multiprocessmodule.MultiprocessService")
			//outgoingIntent.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
			var customSettingsLedger = JSONObject()
			var intentCustomRecord = JSONObject()

			// OOOOOOH NESTED FOR LOOPS. Bad
			// For each modules data record...
			for(module in moduleFileRegistries.keys()){
				// load the record....
				var moduleFileList = moduleFileRegistries.getJSONArray(module)
				/*
					This is special for when the file doesn't exist. I capture its index in the clipData (or data)
					and save that index in the customJSON. This allows multiprocess intent to retrieve it. I don't
					like how reliant these modules are, so I will have to separate them in the future
				*/
				var clipDataIndex = JSONObject()

				// and search through its file listing.... (-1 for proper index
				for(index in 0..moduleFileList.length()-1){
					var filename = moduleFileList.getString(index)
					// To see if it matches the type of data we are looking for...
					for(extension in fileExtensions) {
						Log.i("Checking for filetype ${extension}")
						if(filename.endsWith(extension)){
							Log.i("Checking for locality file ${filename}")
							// Hacky, but should work
							var exists = File(filesDir,filename).exists()
							Log.i("Does ${filename} exist? ${exists}")
							var file = File(filesDir,filename)
							// If it exists, just add it on. If it doesn't exist, make it and do all the rest
							when(exists) {
								true -> {
									when {
										// if there is only one or it's the last one, make it the uri
										outgoingIntent.data == null -> outgoingIntent.setData(FileProvider.getUriForFile(this.applicationContext, "com.example.sapphireassistantframework.fileprovider", file))
										// No clipData exists, and it will need one
										outgoingIntent.clipData == null -> outgoingIntent.clipData = ClipData.newRawUri("FILEDATA", FileProvider.getUriForFile(this.applicationContext, "com.example.sapphireassistantframework.fileprovider", file))
										// Clip data exists, and there is more to add on!
										// I don't... see what is happening here....
										outgoingIntent.clipData != null -> outgoingIntent.clipData?.addItem(ClipData.Item(FileProvider.getUriForFile(this.applicationContext, "com.example.sapphireassistantframework.fileprovider", file)))
										// This should be shorthand for requesting transfer OR bridge. doesn't need to be serveFile
									}
								}
								false -> {
									Log.v("${moduleFileList.getString(index)} is not yet local, it seems")
									if((fileRequestDestinationModules.contains(module) == false)){
										Log.d("Adding ${module} to multiprocessRoute list")
										fileRequestDestinationModules.add(module)
									}else{
										Log.d("This module has already been added to the stack")
									}
									when{
										outgoingIntent.data == null -> {
											var uri = FileProvider.getUriForFile(this.applicationContext,"com.example.sapphireassistantframework.fileprovider",file)
											outgoingIntent.setData(uri)
											// the filename is the key, and the index is the size of clipdata, stored as the value
											clipDataIndex.put("-1",file.name)
											Log.d("${file.name} is the last uri needed. Adding to Data")
											var module = module.split(";")
											Log.v("Granting permission to ${module[0]}")
											grantUriPermission(module[0],uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
											outgoingIntent.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
										}
										// No clipData exists, and it will need one
										outgoingIntent.clipData == null -> {
											var uri = FileProvider.getUriForFile(this.applicationContext,"com.example.sapphireassistantframework.fileprovider",file)
											outgoingIntent.clipData = ClipData.newRawUri("FILEDATA",uri)
											// the filename is the key, and the index is the size-1 of clipdata, stored as the value
											Log.d("Creating clipData w/ uri ${file.name}")
											clipDataIndex.put((outgoingIntent.clipData!!.itemCount-1).toString(),file.name)
											var module = module.split(";")
											grantUriPermission(module[0],uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
										}
										// Clip data exists, and there is more to add on!
										outgoingIntent.clipData != null -> {
											var uri = FileProvider.getUriForFile(this.applicationContext,"com.example.sapphireassistantframework.fileprovider",file)
											outgoingIntent.clipData!!.addItem(ClipData.Item(uri))
											// the filename is the key, and the index is the size-1 of clipdata, stored as the value
											Log.d("Adding uri for ${file.name} to clipData")
											var module = module.split(";")
											clipDataIndex.put((outgoingIntent.clipData!!.itemCount-1).toString(),file.name)
										}
									}
								}
							}
						}
					}
					// JSON makes it easy to send around arbitrary data. I can deal with optimization later
					// It goes at this level, because I need one customRecord per Module, not per file type
					intentCustomRecord = customMultiprocessTemp(clipDataIndex)
					intentCustomRecord.put("MODULE",module)
					customSettingsLedger.put(module,intentCustomRecord)
				}
			}
			Log.d("Final customJSON: ${customSettingsLedger}")
			// If there are no files needed from other modules, we're good to go. Otherwise, we gotta do all this
			if(customSettingsLedger.length() != 0) {
				Log.v("Adding from customSettingsLedger")
				outgoingIntent.putExtra("CUSTOM_MULTIPROCESS", customSettingsLedger.toString())
				var newRoute = ""
				for (value in fileRequestDestinationModules) {
					Log.v("Adding new route data")
					when(newRoute.isNullOrBlank()) {
						true -> newRoute += "(${value}"
						false -> newRoute += ",${value}"
					}
				}
				if(newRoute.isNotBlank()) {
					newRoute += ")"
					// This is hacky. It's here to add a route for multiprocess intent. I don't like how much the core is tied in to it
					if (outgoingIntent.hasExtra(ROUTE)) {
						outgoingIntent.putExtra(ROUTE, "${newRoute},${outgoingIntent.getStringExtra(ROUTE)}")
					} else {
						outgoingIntent.putExtra(ROUTE, newRoute)
					}
				}
				Log.i("New route: ${outgoingIntent.getStringExtra(ROUTE)}")
			}
			// I need to send this info w/ the multiprocess, or have it waiting. Like a dual multiprocess
			var defaultsJSON = loadTable(DEFAULT_MODULES_TABLE)
			Log.i("Trying to load ${MULTIPROCESS}")
			var multiprocessor = defaultsJSON.getString(MULTIPROCESS)
			Log.v("Sending the user to ${multiprocessor}")
			var pendingIntent = pendingIntentLedger.get(multiprocessor)!!
			// This is a test
			pendingIntent.send(this,1,outgoingIntent)
		}catch(exception: Exception){
			Log.e("Check the way you are removing items from the list. Seems like it will cause bugs")
			exception.printStackTrace()
		}
	}

	// This creates the custom settings for a single intent
	// These aren't extras, because that would require multiple intents.
	fun customMultiprocessTemp(clipDataIndexs: JSONObject): JSONObject{
		// This is the custom record for a single modules intents
		var customIntentRecord = JSONObject()
		// Contains the file names. JsonArray. Level 3
		var data_keys = JSONArray()
		customIntentRecord.put("ACTION", ACTION_MANIPULATE_FILE_DATA)

		// I need to be able to send the file URIs w/ this
		for (key in clipDataIndexs.keys()) {
			// DATA_KEYS holds the filenames
			data_keys.put(key)
		}
		// Populate the data keys for the skill modules to know w/ files to write
		customIntentRecord.put(DATA_KEYS, data_keys)
		// This is for retrieval w/ data_Keys, in the processor
		customIntentRecord.put("DATA_CLIP", clipDataIndexs)
		return customIntentRecord
	}

	// This checks for a local copy and serves it if it exists. It needs renaming
	// This is not properly set up.
	fun checkForLocal(intent: Intent){
		// Not sure I need this, but why not
		var outgoingIntent = Intent(intent)
		if(outgoingIntent.clipData == null){
		}
		// Get them filenames...
		var filenames = intent.getStringArrayListExtra(DATA_KEYS)!!
		for(filename in filenames){
			var file = File(filesDir,filename)
			var uri = FileProvider.getUriForFile(this.applicationContext,"com.example.sapphireassistantframework.fileprovider",file)
			// This is an easy enough shortcut. Just put the *last* one in the data slot. The order *really* matters here though, pay attention!
			when(file.exists()){
				// There was some kind of error
				filenames.size <= 0 -> Log.d("There was a checkForLocal error")
				// if there is only one or it's the last one, make it the uri
				filenames.size == 1 -> {
					outgoingIntent.setData(uri)
				}
				// No clipData exists, and it will need one
				outgoingIntent.clipData == null -> {
					outgoingIntent.clipData = ClipData.newRawUri("FILEDATA", uri)
				}
				// Clip data exists, and there is more to add on!
				filenames.size > 1 -> {
					outgoingIntent.clipData!!.addItem(ClipData.Item(uri))
				}
				// This is supposed to generate the file transfer request if it doesn't exist...
				// This doesn't strike me as right, it's not DOING anything
				false -> serveFile(intent)
			}
			// I actually think this is going to cause an error
			filenames.removeAt(0)
		}
		// give permission to access these URIS. The should be readable, not editable
		outgoingIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		startPendingService()
	}

	// I already did all of the prepping in request for local, so just redirect it?
	fun requestTransfer(intent: Intent){
		var outgoingIntent = Intent(intent)
		Log.v("Generating Core file for ${outgoingIntent.getStringArrayListExtra(DATA_KEYS)}")
		// This is temporary
		var module = outgoingIntent.getStringExtra("TO")!!
		var packageClass = module.split(";")
		outgoingIntent.setClassName(packageClass[0],packageClass[1])
		// Do I need to hand it the URI?

		// I have to figure out what to do with this intent
		var pendingIntent = pendingIntentLedger.get(module)!!
		pendingIntent.send(this,1,outgoingIntent)
	}

	fun requestBridge(intent: Intent){
		// Only reason I am not doing a socket, is because of network permissions
		TODO("This will create a temp file, to pass the uri along. the temp file acts as a pipe")
	}

	// This is meant to bridge, or serve internal. This is not at all
	fun serveFileProper(intent: Intent) {
		Log.i("Serving a file")
		// I'm just going to assume a chatty protocol
		// get file list from the module, in DATA_KEYS? <- yes
		when (intent.extras != null) {
			// a flag for acting as a bridge
			intent.hasExtra("FILE_PROVIDER") -> requestBridge(intent)
			// This is for transferring data TO the core. Needs to be formalized
			intent.hasExtra("TRANSFER") -> serveFile(intent)
			// Send out the Uris if they exist
			else -> checkForLocal(intent)
		}
	}

	// I don't see the logic to pass along this information...
	fun serveFile(intent: Intent){
		try{
			var manipulateIntent = Intent()
			//intent.getStringExtra(FROM)
			manipulateIntent.setClassName("com.example.calendarskill","com.example.calendarskill.CalendarModuleInstallServiceRefined")
			manipulateIntent.setAction(ACTION_MANIPULATE_FILE_DATA)
			var counter = 0

			for(key in intent.getStringArrayListExtra(DATA_KEYS)!!){
				Log.v("Generating Core file for ${key} from ${intent.getStringExtra(FROM)}")
				var file = File(filesDir,key)
				// I don't know if this is needed...
				file.createNewFile()
				var uri = FileProvider.getUriForFile(this.applicationContext,"com.example.sapphireassistantframework.fileprovider",file)
				// Add the filedata to the intent
				if(counter == 0) {
					manipulateIntent.setDataAndType(uri, contentResolver.getType(uri))
					manipulateIntent.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
				}else {
					var item = ClipData.Item(uri)
					when(manipulateIntent.clipData){
						null -> manipulateIntent.clipData = ClipData.newRawUri(DATA_KEYS,uri)
						else -> manipulateIntent.clipData!!.addItem(item)
					}
				}
				counter++
			}


		}catch(exception: Exception){
			Log.d("There was an error generating the coreFiles and sending the URIs")
		}
	}

	// Is this when redundant w/ validate?
	fun handleRoute(intent: Intent){
		Log.v("Handling a route. Next module or new route?")
		when(intent.hasExtra(ROUTE)){
			true -> nextModule(intent)
			false -> handleNewInput(intent)
		}
	}

	// Can this be wrapped in to nextModule or handleNewInput
	fun forwardRegistration(intent: Intent){
		// I don't think the incoming intent can propagate
		var outgoingIntent = Intent(intent)
		when(outgoingIntent.getStringExtra(FROM)){
			"${this.packageName};${this.packageName}.CoreRegistrationService" -> {
				outgoingIntent.setAction(ACTION_SAPPHIRE_MODULE_REGISTER)
				outgoingIntent.setClassName(intent.getStringExtra(MODULE_PACKAGE)!!,intent.getStringExtra(MODULE_CLASS)!!)
				startRegistrationService(connection,outgoingIntent)
			}
			else -> {
				outgoingIntent.setClassName(PACKAGE_NAME,"${this.packageName}.CoreRegistrationService")
				startService(outgoingIntent)
			}
		}
	}

	fun nextModule(intent: Intent){
		// This gets the next module in line
		var route = intent.getStringExtra(ROUTE)!!
		// This needs to be expanding the route variable to an actual place
		var cleanedRoute = expandRoute(route).split(",")
		var nextRoute = ""
		when(cleanedRoute.size){
			0 -> nextRoute = cleanedRoute.toString()
			else -> nextRoute = cleanedRoute.get(0)
		}

		Log.d(intent.getStringExtra(ROUTE)!!)
		var module = nextRoute.split(";")

		intent.putExtra(ROUTE,route)

		intent.setClassName(module.component1(),module.component2())
		var validatedIntent = validatePostage(intent)
		// This should be the go to for whatever is next in the route
		if(pendingIntentLedger.containsKey(nextRoute)) {
			var pendingIntent = pendingIntentLedger.get(nextRoute)!!
			Log.d(pendingIntent.toString())
			pendingIntent.send(this, 3, validatedIntent)
		}else{
			Log.d("There is a pending intent ledger. Look through it for ${nextRoute}\n${pendingIntentLedger}")
		}
	}

	fun handleNewInput(intent: Intent){
		// Does this load from the proper place (such as a local file)?
		Log.v("Handling new input")
		var routeTable = loadTable(ROUTE_TABLE)
		Log.v("Loaded route table: ${routeTable}")
		when{
			// This isn't doing a conversion for default? Maybe it is in nextModule
			routeTable.has(intent.getStringExtra(FROM)) -> {
				intent.putExtra(ROUTE,routeTable.getString(intent.getStringExtra(FROM)!!))
				Log.v("Hit for ${intent.getStringExtra(FROM)}")
				Log.v("Heading along route ${intent.getStringExtra(ROUTE)}")
				nextModule(intent)
			}
			else -> defaultPath(intent)
		}
	}

	fun defaultPath(intent: Intent){
		Log.e("There is no default path")
	}

	fun initialize(intent: Intent){
		Log.v("Initializing")
		// Might want to try/catch this
		for(key in intent.getStringArrayListExtra(DATA_KEYS)!!){
			Log.d("Offloading PendingIntent for ${key}")
			// Whelp, just load it up...
			pendingIntentLedger.put(key,intent.getParcelableExtra(key)!!)
		}
		startBackgroundServices()
		initialized = true
	}

	fun startBackgroundServices(){
		Log.i("Starting background services")
		var jsonBackgroundTable = loadTable(BACKGROUND_TABLE)
		Log.i(jsonBackgroundTable.toString())
		for(recordName in jsonBackgroundTable.keys()){
			var record = JSONObject(jsonBackgroundTable.getString(recordName))

			var startupIntent = Intent()
			startupIntent.putExtra(ROUTE,record.getString(STARTUP_ROUTE))
			startupIntent.putExtra(POSTAGE,loadTable(DEFAULT_MODULES_TABLE).toString())
			Log.v(startupIntent.getStringExtra(ROUTE)!!)
			var module = startupIntent.getStringExtra(ROUTE)!!.split(",").get(0).split(";")
			startupIntent.setClassName(module.component1(),module.component2())
			when(record.getBoolean("bound")){
				true -> startBoundService(startupIntent)
				else -> startService(startupIntent)
			}
		}
	}

	fun startBoundService(boundIntent: Intent){
		var connection = Connection()

		if (packageManager.resolveService(boundIntent,0) != null) {
			bindService(boundIntent, connection, Context.BIND_AUTO_CREATE)
		} else {
			Log.e("PackageManager says the service doesn't exist")
		}
		// This appends it to the class variable, so I can shut it down later
		// I don't like how it's a pair
		connections.plus(Pair("${boundIntent.`package`};${boundIntent.component}",connection))
	}

	// Run through the registration process
	fun startRegistrationService(){
		Log.i("Starting registration service")
		var registrationIntent = Intent().setClassName(this.packageName,"${this.packageName}.CoreRegistrationService")
		registrationIntent.setAction(ACTION_SAPPHIRE_INITIALIZE)
		Log.v("starting service ${"${this.packageName}.CoreRegistrationService"}")
		startService(registrationIntent)
	}

	override fun onDestroy() {
		notificationManager.cancel(1337)
		super.onDestroy()
	}
}