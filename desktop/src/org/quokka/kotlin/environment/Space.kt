package org.quokka.kotlin.environment

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.decals.CameraGroupStrategy
import com.badlogic.gdx.graphics.g3d.decals.Decal
import com.badlogic.gdx.graphics.g3d.decals.DecalBatch
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.loader.ObjLoader
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.viewport.ScalingViewport
import org.quokka.Screens.IndexScreen
import org.quokka.game.desktop.GameInitializer
import org.quokka.kotlin.internals.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList
import kotlin.concurrent.thread
import kotlin.concurrent.timer
import kotlin.math.cos
import kotlin.math.sin


/**
 * A screen instantiation which renders the point cloud.
 * The main meat of the application which shows the main screen, controls the flow of data and connects to the backend.
 */
class Space(val recordingId: Int = 1, val local: Boolean = false, val filepath: String = "core/assets/sample.bag") : Screen {

    companion object {
        const val FIXED_CAM_RADIUS_MAX = 100f
        const val FIXED_CAM_RADIUS_MIN = 5f
        const val FIXED_CAM_ANGLE_MIN = 0f
        const val FIXED_CAM_ANGLE_MAX = Math.PI.toFloat() * 0.49f
        const val ZOOM_STEP_SIZE = 10f
        const val CAM_SPEED = 0.03f
        const val AUTOMATIC_CAMERA_SPEED_MODIFIER = 3f
        const val ROTATION_ANGLE_MODIFIER = 1f
        const val MAX_LIDAR_FPS = 20
    }

    private lateinit var plexer: InputMultiplexer
    private val newLidarFps = AtomicBoolean(false)
    /*
     * The frame fetching loop runs at a constant 20fps. These two numbers just determine how many of these frames
     * have to be skipped to achieve the desired framerate.
     * For example 20fps means 0 frames are skipped. 10fps however mean 1 frame is skipped and 5 fps means 3 frames
     * are skipped.
     */
    private val framesToSkip = AtomicInteger(0)
    private val frameFetchSkipCounter = AtomicInteger(0)
    private val lastFpsValue = AtomicInteger(0)

    //settings are stored in a Preferences libgdx object, it is a hashmap
    private val prefs = Gdx.app.getPreferences("My Preferences")


    private var lidarFPS = prefs.getInteger("LIDAR FPS") //lidar fps 5/10/20

    //-------  Camera  -------
    private var fixedCamera = prefs.getBoolean("FIXED CAMERA")
    private var automaticCamera = prefs.getBoolean("AUTOMATIC CAMERA")
    private var gpsEnv = AtomicBoolean(prefs.getBoolean("GPS ENVIRONMENT",false))
    private var fixedCamAzimuth = 0f
    private var fixedCamAngle = Math.PI.toFloat() * 0.3f
    private var fixedCamDistance = 70f


    val settings = GameInitializer.settings
    var pause = AtomicBoolean(false)
    val buffer: Buffer = PrerecordedBuffer(recordingId)

    // this is basically the timestamp
    private var framesIndex = Database.getRecording(recordingId)!!.minFrame


    var cam = PerspectiveCamera(67F, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())

    private var environment = Environment()

    var stage: Stage = Stage(ScalingViewport(Scaling.stretch, 1280f, 720f))
    private var font: BitmapFont = BitmapFont()
    private var label = Label(" ", LabelStyle(font, Color.WHITE))
    private var string: StringBuilder = StringBuilder()

    private var decalBatch = DecalBatch(CameraGroupStrategy(cam))


    private val pix = Pixmap(1, 1, Pixmap.Format.RGB888)
    var decalTextureRegion = TextureRegion(Texture(pix))


    private lateinit var localFrames: ConcurrentLinkedQueue<LidarFrame>


    private var decals: List<Decal> = listOf()

    private val blueRedFade = Array(256) { i ->
        val pix = Pixmap(1, 1, Pixmap.Format.RGB888)
        pix.setColor(i / 255f, 0f, 1 - i / 255f, 1f)
        pix.drawPixel(0, 0)
        TextureRegion(Texture(pix))
    }

    /**
     * The compression level to be used. Contains information about the level, gradual compression and at what distance
     * it should trigger.
     */
    var compressionLevel = Compression(
            prefs.getInteger("COMPRESSION"),
            prefs.getBoolean("GRADUAL COMPRESSION"),
            prefs.getInteger("DISTANCE"),
            this
    )

    // List of timers which run in the background, these have to be discarded once the screen is over.
    private val timers = mutableListOf<Timer>()

    //variables used in the rendering of the globe and 3d gps coords
    private lateinit var modelBatch: ModelBatch
    private lateinit var renderableObjects: ArrayList<ModelInstance>
    private lateinit var gpsobj : ModelInstance
    private lateinit var globe :ModelInstance
    private lateinit var globeTexture :Texture

    init {
        println("end of initializing space")
    }

    lateinit var gui: GuiButtons

    private fun create() {
        gui = GuiButtons(this)
        settings.updateSpace()


        //-----------Camera Creation------------------
        cam.position[0f, 0f ] = 30f
        cam.lookAt(0f, 0f, 0f)
        cam.near = .01f
        cam.far = 1000f
        cam.update()



        //---------Environment Creation --------
        environment.set(ColorAttribute(ColorAttribute.AmbientLight, 0.4f, 0.4f, 0.4f, 1f))
        environment.add(DirectionalLight().set(0.8f, 0.8f, 0.8f, -1f, -0.8f, -0.2f))


        //---------Model Population----------

        pix.setColor(66f / 255, 135f / 255, 245f / 255, 1f)
        pix.drawPixel(0, 0)


        // -----------Bottom Text--------
        stage.addActor(label)


        if (local) {
            localFrames = ConcurrentLinkedQueue()
            timers.add(initLocalFileThread())
        }

        timers.add(initFrameUpdateThread())

        stage.addActor(label)

        plexer = InputMultiplexer(stage)
        Gdx.input.inputProcessor = plexer


        //--------Earth and City-------
        modelBatch = ModelBatch()

        val load = ObjLoader()
        val model = load.loadModel(Gdx.files.internal("ma_place.obj"));
        gpsobj = ModelInstance(model)
        gpsobj.transform.rotate(Vector3.X,90f)


        globeTexture = Texture(Gdx.files.internal("yeet.jpeg"),false)

        renderableObjects = ArrayList(2)
        if(gpsEnv.get()) {
            renderableObjects.add(gpsobj)
        }
    }


    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)

        campButtonpress()
        gui.bar.update()
        // if the camera is fixed that means it's always looking at the center of the environment
        // This is also triggers if the automatic camera is chosen
        if (fixedCamera || automaticCamera) {
            if (automaticCamera) {
                moveAutomaticCamera(delta)
            }
            updateFixedCamera()
        }

//        Gdx.gl.glViewport(0, 0, Gdx.graphics.width, Gdx.graphics.height)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)


        //Frustum culling. Objects are only rendered if they are visible to the camera
        decals.forEach { d ->
            if (cam.frustum.boundsInFrustum(d.x, d.y, d.z, .3f, .3f, .3f) == true) {
                decalBatch.add(d)
                d.lookAt(cam.position, cam.up)
            }
        }


        //render the decals
        decalBatch.flush()
        val material = Material(TextureAttribute.createDiffuse(globeTexture))
        val modelBuilder = ModelBuilder()



        val globePositions = globeUpdate()
        //create globe object with new camera position
        globe = ModelInstance(modelBuilder.createSphere(1f,1f,1f,15,15,
                material,
                (VertexAttributes.Usage.Position or VertexAttributes.Usage.TextureCoordinates or VertexAttributes.Usage.Normal.toLong().toInt()).toLong()),
                globePositions.x,globePositions.y,globePositions.z)
        //rotate globe object so its texture, an image of earth, has its north on the z axis
        globe.transform.rotate(Vector3.X,90F)
        //render city and earth
        renderableObjects.add(globe)

        //render 3d objects, globe and if active, 3d map of gps coords
        modelBatch.begin(cam);
        modelBatch.render(renderableObjects, environment);
        modelBatch.end();
        renderableObjects.remove(globe)


        // Lable situated at the bottom of the screen
        // useful tool for debugging
        // just add strings to the string object to be displayed
        string.setLength(0)
        string.append("fps = ")
                .append(Gdx.graphics.framesPerSecond)
        label.setText(string)
        stage.act(Gdx.graphics.getDeltaTime())
        stage.draw()
    }

    /**
     * This methods is called every tenth of a seconds
     * to load new data in the environment by changing
     * the global variable decal
     * which is both a List<Decal>
     * @author Till, Robert
     */
    private fun initFrameUpdateThread(): Timer {
        return timer("Frame Fetcher", period = 1000 / MAX_LIDAR_FPS.toLong(), initialDelay = 1000 / MAX_LIDAR_FPS.toLong()) {
            // Skip frames according to fps
            if (frameFetchSkipCounter.incrementAndGet() > framesToSkip.get()) {
                frameFetchSkipCounter.set(0)
                if (!pause.get()) {
                    if (compressionLevel.compressionLevel != 1) {
                        decals = fetchNextFrame()?.let { compressionLevel.compressPoints(it) } ?: decals
                        decals.forEach { colorDecal(it, blueRedFade) }
                    } else {
                        fetchNextFrame()?.let { f ->
                            decals = f.coords.map {
                                val d = Decal.newDecal(0.15f, 0.15f, decalTextureRegion)
                                d.setPosition(it.x, it.y, it.z)
                                colorDecal(d, blueRedFade)
                                d
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Only used when the local argument is enabled. Parses the provided bag file every 2 seconds for 20 seconds of
     * footage.
     *
     * @author Till
     */
    private fun initLocalFileThread(): Timer {
        return timer("File Parser", period = 2000) {
            if (localFrames.size < 60) {
                val frames = LidarReader().readLidarFramesInterval(path = filepath, start = framesIndex, end = framesIndex + 12)
                framesIndex += 12
                println("printtttt $framesIndex")
                localFrames.addAll(frames)
            }
        }
    }

    /**
     * Fetches a new frame from either local or database source.
     * @return Null or a lidar frame
     */
    private fun fetchNextFrame(): LidarFrame? {
        if (local) {
            return localFrames.poll()
        } else {
            return buffer.nextFrame()
        }
    }

    /**
     * Helper function which changes the texture of a decal based on its Z coordinate.
     * The percentage of its z coordinate on a relative scale is calculated and then the
     * appropriate texture region is chosen.
     * The effect works best if the array of textures is a color gradient.
     *
     * @param d The decal to be recolored.
     * @param textures An array of texture regions to pick from. Must be non empty.
     */
    private fun colorDecal(d: Decal, textures: Array<TextureRegion>) {
        val minZ = -10
        val maxZ = 15
        var perc = (d.position.z - minZ) / (maxZ - minZ)
        if (perc < 0f)
            perc = 0f
        else if (perc > 1f)
            perc = 1f
        d.textureRegion = textures.get((perc * (textures.size - 1)).toInt())
    }

    /**
     * this methods updates the LidarFPS
     * LidarFPS being the rate at which data is loaded
     * default is 10, the other two options are 5 and 20
     */
    fun changeLidarFPS(newLFPS: Int) {
        lidarFPS = newLFPS // There was an +2 here, not sure why?
        newLidarFps.set(true)
        framesToSkip.set(MAX_LIDAR_FPS / lidarFPS - 1)
        // Reset the buffer to load new footage based on fps
        // Do not remove the ?. For some reason buffer can be null even though it is initialized as a val at creation.
        if (lidarFPS != lastFpsValue.getAndSet(lidarFPS)) {
            buffer?.let {
                thread {
                    it.clear()
                }
            }
        }
    }

    /**
     * Updates the resolution of the application
     */
    fun changeResolution(height: Int, width: Int) {
        Gdx.graphics.setWindowedMode(width, height);
    }

    /**
     * Switch Fixed Camera
     */
    fun switchFixedCamera(fixed: Boolean) {
        this.fixedCamera = fixed
        prefs.putBoolean("FIXED CAMERA",fixedCamera)
        settings.camera_checkbox.isChecked = fixed
    }

    /**
     * Switch automatic camera
     */
    fun switchAutomaticCamera(automatic: Boolean) {
        this.automaticCamera = automatic
        prefs.putBoolean("AUTOMATIC CAMERA",automaticCamera)
        settings.automatic_camera_checkbox.isChecked = automatic
    }

    /**
     * Skip 10 frames forwards
     */
    fun skipForward10frames() {
        this.framesIndex += 10
        buffer.skipForward(5f)
    }

    /**
    * Toggle gps env
    */
    private fun toggleGPS(toggle : Boolean) {
        this.gpsEnv.set(toggle)
        prefs.putBoolean("GPS ENVIRONMENT",gpsEnv.get())
//        settings.gps_environment_checkbox.isChecked = toggle
        if(gpsEnv.get()){
            renderableObjects.add(gpsobj)
        } else {
            renderableObjects.remove(gpsobj)
        }
    }


    /**
     * Skip 10 frames backwards
     */
    fun skipBackwards10Frames() {
        this.framesIndex -= 10
        buffer.skipBackward(5f)
    }


    override fun dispose() {
        for (t in timers) {
            t.cancel()
        }
        decalBatch.dispose()
    }

    override fun resume() {
        pause.set(false)
    }

    override fun resize(width: Int, height: Int) {
        stage.clear()
        stage.viewport.update(1280, 720, true)
        gui.images.forEach { stage.addActor(it) }
        gui.draw()
        stage.viewport.update(width, height, true);
    }

    override fun pause() {
        pause.set(true)
    }

    override fun hide() {
        this.dispose()
    }

    override fun show() {
        changeResolution(720,1280)
        create()
        stage.viewport.update(Gdx.graphics.width,Gdx.graphics.height)
//        changeResolution(Gdx.graphics.height, Gdx.graphics.width)
    }



    //-------Revised Camera Control Methods-----------------------

   /**
    * @author Robert
    * This method was used for testing initially,
    * it transformed into the main keyboard observer
    * all the buttons work with the HUD on
    * but it is mainly intended for use with the HUD turned off
    */
    private fun campButtonpress() {

        val delta = Gdx.graphics.deltaTime

       if(fixedCamera == false && automaticCamera == false ) {

           //rotation of camera button
           if (Gdx.input.isKeyPressed(Input.Keys.W)) {
               rotateUp(delta * 50)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.S)) {
               rotateDown(delta * 50)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.A)) {
               rotateLeft(delta * 50)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.D)) {
               rotateRight(delta * 50)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.Q)) {
               rollRight(delta*40)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.E)) {
               rollLeft(delta*40)
           }

           //movement of camera buttons
           if (Gdx.input.isKeyPressed(Input.Keys.UP)) {
               moveUp(delta*200)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
               moveDown(delta*200)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
               moveLeft(delta*200)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
               moveRight(delta*200)
           }
           if (Gdx.input.isKeyJustPressed(Input.Keys.R)) {
               moveForward(delta)
           }
           if (Gdx.input.isKeyJustPressed(Input.Keys.F)) {
               moveBackward((delta))
           }

           if (Gdx.input.isKeyPressed(Input.Keys.X)) {
               resetCamera()
           }
       } else {
           //move buttons while camera is fixed
           if (Gdx.input.isKeyPressed(Input.Keys.W)) {
               moveFixedUp(delta * 50)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.S)) {
               moveFixedDown(delta * 50)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.A)) {
               rotateFixedLeft(delta * 50)
           }
           if (Gdx.input.isKeyPressed(Input.Keys.D)) {
               rotateFixedRight(delta * 50)
           }

           if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
               zoomFixedCloser()
           }
           if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
               zoomFixedAway()
           }

           if (Gdx.input.isKeyPressed(Input.Keys.X)) {
               resetFixed()
           }
       }

       //other kye bindings
        if (Gdx.input.isKeyJustPressed(Input.Keys.K)) {
            switchAutomaticCamera(!automaticCamera)
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.L)) {
            switchFixedCamera(!fixedCamera)
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1)) {
            compressionLevel.changeCompression(1)
            prefs.putInteger("COMPRESSION",1)
            settings.compression_box.selected = 1
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_2)) {
            compressionLevel.changeCompression(2)
            prefs.putInteger("COMPRESSION",2)
            settings.compression_box.selected = 2
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_3)) {
            compressionLevel.changeCompression(3)
            prefs.putInteger("COMPRESSION",3)
            settings.compression_box.selected = 3
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_4)) {
            compressionLevel.changeCompression(4)
            prefs.putInteger("COMPRESSION",4)
            settings.compression_box.selected = 4
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            GameInitializer.screen = IndexScreen()
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            if(pause.get() == false ){
                pause()
            } else {
                resume()
            }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.COMMA)) {
            skipBackwards10Frames()
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.PERIOD)) {
            skipForward10frames()
        }

       if (Gdx.input.isKeyJustPressed(Input.Keys.H)) {
           gui.hideHUD()
           prefs.putBoolean("HIDE HUD", gui.hidden)
       }

       if (Gdx.input.isKeyJustPressed(Input.Keys.C)) {
           compressionLevel.switchGradualCompression(!compressionLevel.gradualCompression)
           settings.gradualBox.isChecked = compressionLevel.gradualCompression
       }

       if(Gdx.input.isKeyJustPressed(Input.Keys.M)) {
           toggleGPS(!gpsEnv.get())
       }

    }



    //----------Till's fixed camera controls---------

    private fun updateFixedCamera() {
        val x = fixedCamDistance * cos(fixedCamAzimuth) * cos(fixedCamAngle)
        val y = -fixedCamDistance * sin(fixedCamAzimuth) * cos(fixedCamAngle)
        val z = fixedCamDistance * sin(fixedCamAngle)
        cam.position.set(x, y, z)
        cam.up.set(Vector3.Z)
        cam.lookAt(Vector3(0f, 0f, 0f))
        cam.update()
    }

    private fun moveAutomaticCamera(delta: Float) {
        rotateFixedRight(delta * AUTOMATIC_CAMERA_SPEED_MODIFIER)
    }

    /**
     * Zoom in closer for the fixed camera only with a fixed ZOOM_STEP_SIZE.
     */
    fun zoomFixedCloser() {
        fixedCamDistance -= ZOOM_STEP_SIZE
        if (fixedCamDistance < FIXED_CAM_RADIUS_MIN)
            fixedCamDistance = FIXED_CAM_RADIUS_MIN
        if (fixedCamDistance > FIXED_CAM_RADIUS_MAX)
            fixedCamDistance = FIXED_CAM_RADIUS_MAX
    }

    /**
     * Zoom out further for the fixed camera only with a fixed ZOOM_STEP_SIZE.
     */
    fun zoomFixedAway() {
        fixedCamDistance += ZOOM_STEP_SIZE
        if (fixedCamDistance < FIXED_CAM_RADIUS_MIN)
            fixedCamDistance = FIXED_CAM_RADIUS_MIN
        if (fixedCamDistance > FIXED_CAM_RADIUS_MAX)
            fixedCamDistance = FIXED_CAM_RADIUS_MAX
    }

    /**
     * Rotate the fixed camera left according to the delta. Modify the CAM_SPEED constant to change how fast the
     * rotation should be.
     */
    fun rotateFixedLeft(delta: Float) {
        fixedCamAzimuth += delta * CAM_SPEED
        fixedCamAzimuth %= Math.PI.toFloat() * 2
    }

    /**
     * Rotate the fixed camera right according to the delta. Modify the CAM_SPEED constant to change how fast the
     * rotation should be.
     */
    fun rotateFixedRight(delta: Float) {
        fixedCamAzimuth -= delta * CAM_SPEED
        fixedCamAzimuth %= Math.PI.toFloat() * 2
    }

    /**
     * Change the angle higher up for the fixed camera only. Modify the CAM_SPEED constant to change how fast the
     * change should be.
     */
    fun moveFixedUp(delta: Float) {
        fixedCamAngle += delta * CAM_SPEED

        if (fixedCamAngle > FIXED_CAM_ANGLE_MAX)
            fixedCamAngle = FIXED_CAM_ANGLE_MAX
        if (fixedCamAngle < FIXED_CAM_ANGLE_MIN)
            fixedCamAngle = FIXED_CAM_ANGLE_MIN

        fixedCamAngle %= Math.PI.toFloat() * 2
    }

    /**
     * Change the angle to be lower for the fixed camera only. Modify the CAM_SPEED constant to change how fast the
     * change should be.
     */
    fun moveFixedDown(delta: Float) {
        fixedCamAngle -= delta * CAM_SPEED

        if (fixedCamAngle > FIXED_CAM_ANGLE_MAX)
            fixedCamAngle = FIXED_CAM_ANGLE_MAX
        if (fixedCamAngle < FIXED_CAM_ANGLE_MIN)
            fixedCamAngle = FIXED_CAM_ANGLE_MIN

        fixedCamAngle %= Math.PI.toFloat() * 2
    }

    /**
     * Reset the fixed camera only.
     */
    fun resetFixed() {
        fixedCamAngle = Math.PI.toFloat() * 0.3f
        fixedCamAzimuth = 30f
        fixedCamDistance = 70f
    }


    //----------Robert's free camera controls------------

    /*
     * @author Robert
     *  here methods for the camera controls can be found
     *  they make use of the camera's up and direction vector
     *  to rotate and move it
     */

    /**
     * Resets the normal camera.
     */
    fun resetCamera() {
        cam.position[0f, 0f] = 30f
        cam.lookAt(0f, 0f, 0f)
        cam.up.set(0f, 1f, 0f)
        cam.update()
    }

    /**
     * Move the normal camera forward a fixed amount based on ZOOM_STEP_SIZE.
     */
    fun moveForward(delta: Float) {
        cam.translate(Vector3(cam.direction).scl(ZOOM_STEP_SIZE))
        cam.update()
    }

    /**
     * Move the normal camera backwards a fixed amount based on ZOOM_STEP_SIZE.
     */
    fun moveBackward(delta: Float) {
        cam.translate(Vector3(cam.direction).scl(-ZOOM_STEP_SIZE))
        cam.update()
    }

    /**
     * Pan the normal camera up based on CAM_SPEED and the current delta.
     */
    fun moveUp(delta: Float) {
        cam.translate(Vector3(cam.up).scl(delta * CAM_SPEED))
        cam.update()
    }

    /**
     * Pan the normal camera down based on CAM_SPEED and the current delta.
     */
    fun moveDown(delta: Float) {
        cam.translate(Vector3(cam.up).scl(-delta * CAM_SPEED))
        cam.update()
    }

    /**
     * Pan the normal camera left based on CAM_SPEED and the current delta.
     */
    fun moveLeft(delta: Float) {
        cam.translate(Vector3(cam.up).rotate(cam.direction, 90f).scl(-delta * CAM_SPEED))
        cam.update()
    }

    /**
     * Pan the normal camera right based on CAM_SPEED and the current delta.
     */
    fun moveRight(delta: Float) {
        cam.translate(Vector3(cam.up).rotate(cam.direction, 90f).scl(delta * CAM_SPEED))
        cam.update()
    }

    /**
     * Rotate the normal camera up based on ROTATION_ANGLE_MODIFIER and the current delta.
     */
    fun rotateUp(delta: Float) {
        cam.rotate(Vector3(cam.up).rotate(cam.direction, 90f), delta * ROTATION_ANGLE_MODIFIER)
        cam.update()

    }

    /**
     * Rotate the normal camera down based on ROTATION_ANGLE_MODIFIER and the current delta.
     */
    fun rotateDown(delta: Float) {
        cam.rotate(Vector3(cam.up).rotate(cam.direction, 90f), -delta * ROTATION_ANGLE_MODIFIER)
        cam.update()
    }

    /**
     * Rotate the normal camera left based on ROTATION_ANGLE_MODIFIER and the current delta.
     */
    fun rotateLeft(delta: Float) {
        cam.rotate(cam.up, delta * ROTATION_ANGLE_MODIFIER)
        cam.update()
    }

    /**
     * Rotate the normal camera right based on ROTATION_ANGLE_MODIFIER and the current delta.
     */
    fun rotateRight(delta: Float) {
        cam.rotate(cam.up, -delta * ROTATION_ANGLE_MODIFIER)
        cam.update()
    }

    private fun rollRight(delta: Float) {
        cam.rotate(cam.direction, -delta * ROTATION_ANGLE_MODIFIER)
        cam.update()
    }

    private fun rollLeft(delta: Float) {
        cam.rotate(cam.direction, delta * ROTATION_ANGLE_MODIFIER)
        cam.update()
    }


    /**
     * This methods calculates the position of the globe object
     * based on the camera's position
     * the position of the globa has to be bottom right fo the camera
     * so the globe is moved forward, left and downwards
     * from the center of the camera
     * the bottom and right move are done upwards and to the left
     * if the application is rotated
     * @author Robert
     */
    private fun globeUpdate():Vector3{
        val forwardScalar = 5f
        var rightScalar = 4.4f
        var downwardsScalar = 2.3f

        if (gui.rotated){
            rightScalar = -4.4f
            downwardsScalar = -2.3f
        }

        val ground = Vector3(cam.position) //start from the center of camera
        val rightWard = Vector3(cam.up).rotate(cam.direction, 90f).scl(rightScalar)
        val forWard = Vector3(cam.direction).scl(forwardScalar)
        val downWard = Vector3(cam.up).scl(-1*downwardsScalar)
        ground.add(forWard)//move forwards
        ground.add(rightWard)//move to right (or left if rotated)
        ground.add(downWard)//move down (or up if rotated)

        return  ground
    }
}


