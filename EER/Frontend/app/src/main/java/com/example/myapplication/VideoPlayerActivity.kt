package com.example.myapplication

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.volley.DefaultRetryPolicy
import com.android.volley.NetworkResponse
import com.android.volley.Request.Method
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.toolbox.HttpHeaderParser
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.bumptech.glide.Glide
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var suggestionsRecyclerView: RecyclerView
    private lateinit var suggestionsAdapter: ResultsAdapter
    private lateinit var llamaQueryText: TextView
    private lateinit var buttonShowLlamaQuery: Button

    private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA)

    private var userEmotion: String = "happy"
    private var expectingAnswerLlama = false
    private var negativeSentimentCounter: Int = 0
    private var duplicateVideos = true
    private var currentQuery = ""
    private var suggestionsAlreadyTriggered = false
    private var llamaUpdatedQuery: String = ""
    private var previousEmbeddingId = -1
    private var currentEmbeddingId = -1
    private var lastSentTime: Long = 0L


    /**
     * Is called on creation of the activity and initializes UI elements, checks permissions,
     * starts the videoplaying, starts the sentiment camera stream and creates an error listener.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initViews()
        extractIntentExtras()
        setupVideoPlayer()
        setupCameraStream()
    }

    /**
     * Initializes all of the necessary views of the Activity
     */
    fun initViews() {
        videoView = findViewById(R.id.videoPlayerView)
        progressBar = findViewById(R.id.loadingSpinner)
        titleView = findViewById(R.id.videoTitle)
        cameraExecutor = Executors.newSingleThreadExecutor()
        suggestionsRecyclerView = findViewById(R.id.suggestionsRecyclerView)
        suggestionsRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        llamaQueryText = findViewById(R.id.llamaQueryText)
        buttonShowLlamaQuery = findViewById(R.id.buttonShowLlamaQuery)
    }

    /**
     * Extracts all of the information / variables from the intent
     */
    private fun extractIntentExtras() {
        val suggestionMode = intent.getStringExtra("suggestionMode") ?: "nearest"
        currentEmbeddingId = intent.getIntExtra("embedding_id", -1)
        previousEmbeddingId = intent.getIntExtra("previous_embedding_id", -1).takeIf { it != -1 } ?: currentEmbeddingId
        duplicateVideos = intent.getBooleanExtra("duplicateVideos", true)
        currentQuery = intent.getStringExtra("currentQuery") ?: ""
    }

    /**
     * Sets up the VideoPlayer and the mediaplayer
     */
    private fun setupVideoPlayer() {
        val videoUrl = intent.getStringExtra("video_url") ?: run {
            Toast.makeText(this, "Missing video URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val frameTime = intent.getDoubleExtra("frame_time", 0.0)
        val imageUrl = intent.getStringExtra("annotated_image")
        val filename = Uri.parse(videoUrl).lastPathSegment ?: "Unknown Video"
        titleView.text = URLDecoder.decode(filename, "UTF-8")

        val imageAnnotation = findViewById<ImageView>(R.id.imageAnnotation)
        val checkboxShowAnnotation = findViewById<CheckBox>(R.id.checkboxShowAnnotation)

        if (!imageUrl.isNullOrBlank() && !imageUrl.contains("null")) {
            Glide.with(this).load(imageUrl).placeholder(android.R.drawable.ic_menu_report_image).into(imageAnnotation)
            checkboxShowAnnotation.visibility = View.VISIBLE
            checkboxShowAnnotation.setOnCheckedChangeListener { _, isChecked ->
                imageAnnotation.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
            imageAnnotation.visibility = View.GONE
        } else {
            checkboxShowAnnotation.visibility = View.GONE
            imageAnnotation.visibility = View.GONE
        }

        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)
        videoView.setVideoURI(Uri.parse(videoUrl))
        videoView.requestFocus()

        videoView.setOnPreparedListener { mp ->
            progressBar.visibility = ProgressBar.GONE
            mp.seekTo((frameTime * 1000).toInt())
            mp.start()
        }

        videoView.setOnCompletionListener {
            Toast.makeText(this, "Playback finished", Toast.LENGTH_SHORT).show()
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.e("VideoPlayer", "Error: what=$what, extra=$extra")
            Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show()
            true
        }
    }




    /**
     * Sends a query request to the search api with two separate IDs. Both of them are embedding IDs
     * with the first ID being the one from the embedding of the last video played and the second
     * one being the embedding ID of the current video played.
     */
    fun fetchDirectionRecommendations() {
        val suggestionMode = intent.getStringExtra("suggestionMode") ?: "nearest"
        val emotionSpinner = intent.getStringExtra("emotion") ?: ""
        val dataType = intent.getStringExtra("dataType") ?: ""
        Log.d("DATATYPE", dataType)
        val url = "http://10.34.64.139:8001/search_by_direction_pair/$dataType/$emotionSpinner/$duplicateVideos/?source_id=$previousEmbeddingId&target_id=$currentEmbeddingId"
        Log.d("DIRECTION_SEARCH", "Fetching from URL: $url")

        val requestQueue = Volley.newRequestQueue(this)
        val stringRequest = StringRequest(
            Method.GET, url,
            { response ->
                try {
                    val jsonArray = JSONArray(response)
                    Log.d("DIRECTION_SEARCH", "Received ${jsonArray.length()} recommendations")

                    val directionResults = mutableListOf<VideoResult>()
                    val baseUrl = "http://10.34.64.139:8001"

                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val videoPath = baseUrl + obj.getString("video_path")
                        val frameTime = obj.getDouble("frame_time")
                        val similarity = obj.getDouble("similarity")
                        val embeddingId = obj.getInt("embedding_id")
                        if (embeddingId == currentEmbeddingId) {
                            continue
                        }
                        var annotatedImage = obj.optString("annotated_image", null)?.let { "$baseUrl/$it" }
                        Log.d("Suggestions", "annotated image is $annotatedImage }}")
                        if (annotatedImage.isNullOrBlank() || annotatedImage.contains("null")) {
                            annotatedImage = null
                        }
                        var frameLocation = obj.optString("frame_location", null)?.let { "$baseUrl/$it" }
                        Log.d("Suggestions", "annotated image is $frameLocation }}")
                        if (frameLocation.isNullOrBlank() || frameLocation.contains("null")) {
                            annotatedImage = null
                        }


                        directionResults.add(
                            VideoResult(
                                videoUrl = videoPath,
                                frameTime = frameTime,
                                annotatedImageUrl = annotatedImage,
                                frameLocation = frameLocation,
                                embeddingID = embeddingId,
                                previousEmbeddingID = currentEmbeddingId
                            )
                        )

                        Log.d("DIRECTION_SEARCH", "Video: $videoPath, Annotated Image:$annotatedImage Time: $frameTime, Similarity: $similarity, Embedding ID: $embeddingId")
                    }

                    runOnUiThread {
                        val directionRecyclerView = findViewById<RecyclerView>(R.id.suggestionsRecyclerView)
                        directionRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
                        directionRecyclerView.adapter = ResultsAdapter(directionResults, this, currentQuery, emotion = emotionSpinner, dataType = dataType, suggestionMode, duplicateVideos)
                        findViewById<TextView>(R.id.suggestionsLabel).visibility = View.VISIBLE
                        findViewById<TextView>(R.id.suggestionsLabel).text = "Displaying Suggestions from Nearest Neighbor Search"
                        directionRecyclerView.visibility = View.VISIBLE
                    }

                } catch (e: Exception) {
                    Log.e("DIRECTION_SEARCH", "Parsing error: ${e.message}")
                }
            },
            { error ->
                Log.e("DIRECTION_SEARCH", "Error: ${error.message}")
                error.networkResponse?.let {
                    val statusCode = it.statusCode
                    val responseBody = String(it.data)
                    Log.e("DIRECTION_SEARCH", "Status code: $statusCode, Body: $responseBody")
                }
            }
        )
        stringRequest.retryPolicy = DefaultRetryPolicy(
            100000,
            DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        )
        requestQueue.add(stringRequest)

        requestQueue.add(stringRequest)
    }



    /**
     * sends a query request to the api that then generates an improved query with a large language
     * model (tinyllama). The result is then sent back.
     */
    fun sendQueryRequestLlama(
        context: android.content.Context,
        query: String
    ) {
        val emotionSpinner = intent.getStringExtra("emotion") ?: ""
        val dataType = intent.getStringExtra("dataType") ?: ""
        val currentEmbeddingId = intent.getIntExtra("embedding_id", -1)
        val suggestionMode = intent.getStringExtra("suggestionMode") ?: "nearest"
        Log.d("DATATYPE", dataType)
        val url = "http://10.34.64.139:8001/ask_llama/$query/$emotionSpinner/$duplicateVideos"

        val requestQueue: RequestQueue = Volley.newRequestQueue(context)

        val stringRequest = StringRequest(
            Method.GET, url,
            Response.Listener<String> { response ->
                Log.i("LLAMA", "Success! Response: $response")
                try {
                    val result = JSONArray(response)
                    val llmResults = mutableListOf<VideoResult>()
                    val baseUrl = "http://10.34.64.139:8001"

                    for (i in 0 until result.length()) {
                        val obj = result.getJSONObject(i)
                        val videoPath = baseUrl + obj.getString("video_path")
                        val frameTime = obj.optDouble("frame_time", 0.0)
                        val embeddingId = obj.getInt("embedding_id")
                        llamaUpdatedQuery = obj.getString("llama_updated_query")
                        Log.d("LLAMA", "new llama query is $llamaUpdatedQuery")
                        llamaQueryText.text = llamaUpdatedQuery.take(50) + "…"
                        runOnUiThread {
                            if (llamaUpdatedQuery.isNotBlank()) {
                                buttonShowLlamaQuery.visibility = View.VISIBLE
                                buttonShowLlamaQuery.setOnClickListener {
                                    val intent = Intent(this, LlamaQueryActivity::class.java)
                                    intent.putExtra("llama_query", llamaUpdatedQuery)
                                    startActivity(intent)
                                }
                            } else {
                                buttonShowLlamaQuery.visibility = View.GONE
                            }
                        }


                        var annotatedImage = obj.optString("annotated_image", null)?.let { "$baseUrl/$it" }
                        Log.d("Suggestions", "annotated image is $annotatedImage }}")
                        if (annotatedImage.isNullOrBlank() || annotatedImage.contains("null")) {
                            annotatedImage = null
                        }
                        var frameLocation = obj.optString("frame_location", null)?.let { "$baseUrl/$it" }
                        Log.d("Suggestions", "annotated image is $frameLocation }}")
                        if (frameLocation.isNullOrBlank() || frameLocation.contains("null")) {
                            annotatedImage = null
                        }

                        llmResults.add(
                            VideoResult(
                                videoUrl = videoPath,
                                frameTime = frameTime,
                                annotatedImageUrl = annotatedImage,
                                frameLocation = frameLocation,
                                embeddingID = embeddingId,
                                previousEmbeddingID = currentEmbeddingId
                            )
                        )
                    }

                    val suggestionMode = intent.getStringExtra("suggestionMode") ?: "nearest"

                    suggestionsAdapter = ResultsAdapter(llmResults, this, query, emotionSpinner, dataType, suggestionMode, duplicateVideos)
                    suggestionsRecyclerView.adapter = suggestionsAdapter


                    runOnUiThread {
                        val llmRecyclerView = findViewById<RecyclerView>(R.id.suggestionsRecyclerView)
                        llmRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
                        llmRecyclerView.adapter = ResultsAdapter(llmResults, this, query = currentQuery, emotion = emotionSpinner, dataType = dataType, suggestionMode, duplicateVideos)
                        findViewById<TextView>(R.id.suggestionsLabel).visibility = View.VISIBLE
                        findViewById<TextView>(R.id.suggestionsLabel).text = "Displaying Suggestions from Large Language Model"
                        llmRecyclerView.visibility = View.VISIBLE
                    }

                } catch (e: Exception) {
                    Log.e("VOLLEY", "JSON Parsing Error: ${e.message}")
                }


            }
        ) { error ->
            Log.e("LLAMA", "Volley Error: ${error.message}")

            if (error.networkResponse != null) {
                val statusCode = error.networkResponse.statusCode
                val responseBody = error.networkResponse.data?.let { String(it) }
                Log.e("LLAMA", "HTTP Status Code: $statusCode")
                Log.e("LLAMA", "Error Response Body: $responseBody")
            }
        }

        stringRequest.retryPolicy = DefaultRetryPolicy(
            100000,
            DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        )
        requestQueue.add(stringRequest)
    }


    /**
     * Method for sending images of the front camera to the sentiment API, which then returns
     * the emotion and confidence of the user.
     */
    fun sendPostRequestSentiment(context: android.content.Context, base64Image: String) {
        val suggestionMode = intent.getStringExtra("suggestionMode") ?: "nearest"
        val url = "http://10.34.64.139:8003/upload_base64" // address of the sentiment api
        val jsonBody = JSONObject()
        jsonBody.put("image", base64Image)
        val requestBody = jsonBody.toString()

        val requestQueue: RequestQueue = Volley.newRequestQueue(context)

        val stringRequest = object : StringRequest(Method.POST, url,
            Response.Listener { response ->
                Log.i("VOLLEY", "Success! Response: $response")

                try {
                    val jsonResponse = JSONObject(response)
                    val sentiment = jsonResponse.optString("sentiment", "Unknown")
                    userEmotion = jsonResponse.optString("emotion", "Unknown")
                    if ((userEmotion == "sad" || userEmotion == "angry" || userEmotion == "fear") && !expectingAnswerLlama) {
                        negativeSentimentCounter++
                        Log.d("LLAMA", "Negative sentiment detected. Count: $negativeSentimentCounter")

                        if (negativeSentimentCounter >= 5 && !suggestionsAlreadyTriggered) {
                            suggestionsAlreadyTriggered = true
                            expectingAnswerLlama = true

                            val query = intent.getStringExtra("currentQuery")
                            val emotionSpinner = intent.getStringExtra("emotion")

                            if (query.isNullOrBlank() || emotionSpinner.isNullOrBlank()) {
                                Log.e("LLAMA", "Missing query or emotion, not sending to LLaMA")
                                return@Listener
                            }
                            val currentEmbeddingId = intent.getIntExtra("embedding_id", -1)

                            Log.d("SUGGESTION", "suggestionMode is $suggestionMode")

                            if (suggestionMode == "nearest") {
                                fetchDirectionRecommendations()
                            }
                            if (suggestionMode == "llm") {
                                sendQueryRequestLlama(this, query)
                            }
                        }
                    }

                    runOnUiThread {
                        findViewById<TextView>(R.id.text).text = "Sentiment: $sentiment\nEmotion: $userEmotion"
                    }

                } catch (e: Exception) {
                    Log.e("VOLLEY", "Error parsing JSON response: ${e.message}")
                }

            },
            Response.ErrorListener { error ->
                Log.e("VOLLEY", "Error: ${error.message}")
                if (error.networkResponse != null) {
                    val statusCode = error.networkResponse.statusCode
                    val responseData = error.networkResponse.data?.let { String(it) } ?: "No response body"
                    Log.e("VOLLEY", "HTTP Status Code: $statusCode")
                    Log.e("VOLLEY", "Response Data: $responseData")
                }

                runOnUiThread {
                    findViewById<TextView>(R.id.text).text = "Error: Could not get response"
                }
            }) {

            override fun getBodyContentType(): String {
                return "application/json; charset=utf-8"
            }

            override fun getBody(): ByteArray {
                return requestBody.toByteArray(Charsets.UTF_8)
            }

            override fun parseNetworkResponse(response: NetworkResponse?): Response<String> {
                val responseString = response?.data?.let { String(it) } ?: "No Response"
                return Response.success(responseString, HttpHeaderParser.parseCacheHeaders(response))
            }
        }

        requestQueue.add(stringRequest)
    }


    /**
     * Camera stream that is always running and captures an image of the face / frontcamera every
     * second.
     */
    private fun setupCameraStream() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()

            val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                it.setAnalyzer(cameraExecutor) { image ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastSentTime >= 1000) {
                        sendPostRequestSentiment(this, imageProxyToBase64(image))
                        lastSentTime = currentTime
                    }
                    image.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                Toast.makeText(this, "Front Camera Streaming Started!", Toast.LENGTH_SHORT).show()
            } catch (exc: Exception) {
                Log.e("CameraStream", "Failed to bind camera", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Shuts down the camera when the app is closed.
     */
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
    }


    /**
     * Converts an ImageProx to Base64 such that it can be sent to the sentiment API.
     */
    fun imageProxyToBase64(image: ImageProxy): String {
        val yBuffer = image.planes[0].buffer // Y
        val vuBuffer = image.planes[2].buffer // VU
        val ySize = yBuffer.remaining()
        val vuSize = vuBuffer.remaining()
        val nv21 = ByteArray(ySize + vuSize)
        yBuffer.get(nv21, 0, ySize)
        vuBuffer.get(nv21, ySize, vuSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
        val imageBytes = out.toByteArray()
        val bitmap =  BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    /**
     * Request all of the Permissions from the user.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            setupCameraStream()
        } else {
            Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // Send results back to MainActivity when clicking on the back arrow
                val resultIntent = Intent()
                setResult(RESULT_OK, resultIntent)
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
