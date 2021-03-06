package org.quokka.kotlin.environment

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.DragListener
import org.quokka.Screens.IndexScreen
import org.quokka.game.desktop.GameInitializer
import org.quokka.kotlin.internals.Buffer
import org.quokka.kotlin.internals.Database

/**
 * @author Gibson
 * This method is used to save and update settings,
 * it also triggers relevant changes to be applied to
 * the Space object class.
*/
class Settings {
    private val font = BitmapFont()
    private val skin = Skin(Gdx.files.internal("Skins/glassy-ui.json"))

    // Swap these if no database connection, but why would you use these if you don't have one?
    // val files = mapOf<String, Int>("Train passby" to 1, "intersection" to 2, "road" to 3)
    val files = Database.recordings.associateBy({it.title}, {it.id})

    private val shared_style = Label.LabelStyle(font, Color.WHITE)

    // labels
    private val prefs = Gdx.app.getPreferences("My Preferences")

    private val lidarFPS = Label("LIDAR FPS", shared_style)
    val lidar_box = SelectBox<Int>(skin)

    private val memory = Label("BUFFER SIZE", shared_style)
    val memory_box = SelectBox<Int>(skin)

    private val resolution = Label("RESOLUTION", shared_style)
    val resolution_box = SelectBox<String>(skin)


    private val compression = Label("COMPRESSION LEVEL", shared_style)
    val compression_box = SelectBox<Int>(skin)


    private val gradualCompression = Label("GRADUAL COMPRESSION", shared_style)
    val gradualBox = CheckBox("", skin)


    private val distance = Label("DISTANCE (DFCM)", shared_style)
    private val distance_field = TextField("", skin) // Todo: dfcm

    private val fixedCamera = Label("FIXED CAMERA", shared_style)
    val camera_checkbox = CheckBox("", skin)

    private val automaticCamera = Label("AUTOMATIC CAMERA", shared_style)
    val automatic_camera_checkbox = CheckBox("", skin)

    private val rotate_label = Label("ROTATE", shared_style)
    val rotate_box = CheckBox("", skin)

    private val hide_hud = Label("HIDE HUD", shared_style)
    val hud_box = CheckBox("", skin)

    private val back_button = TextButton("BACK", skin)
    private val save_button = TextButton("SAVE", skin)


    val dialog = Dialog("", skin)

    init {
        lidar_box.setItems(5, 10, 20)
        lidar_box.selected = prefs.getInteger("LIDAR FPS", 10)
        distance_field.textFieldFilter = TextField.TextFieldFilter.DigitsOnlyFilter()

        memory_box.setItems(5, 10, 15, 20, 30, 60)
        memory_box.selected = prefs.getInteger("MEMORY", 30)

        resolution_box.setItems("1920x1080", "1280x720", "FULLSCREEN")
        resolution_box.selected = prefs.getString("RESOLUTION", "1280x720")
        camera_checkbox.isChecked = prefs.getBoolean("FIXED CAMERA", true)
        automatic_camera_checkbox.isChecked = prefs.getBoolean("AUTOMATIC CAMERA", false)

        compression_box.setItems(1, 4, 3, 2)
        compression_box.selected = prefs.getInteger("COMPRESSION", 4)
        gradualBox.isChecked = prefs.getBoolean("GRADUAL COMPRESSION", false)
        distance_field.text = prefs.getInteger("DFCM",15).toString()

        rotate_box.isChecked = prefs.getBoolean("ROTATE", false)

        hud_box.isChecked = prefs.getBoolean("HIDE HUD", false)

        dialog.setSize(200f, 250f)
        dialog.setPosition(Gdx.graphics.width / 2 - 100f, Gdx.graphics.height / 2 - 101f)
        dialog.contentTable.defaults().pad(10f)
        dialog.color = Color(Color.GRAY.r, Color.GRAY.g, Color.GRAY.b, 1f)


        dialog.contentTable.add(Label("PREFERENCES", shared_style))
        dialog.contentTable.row()

        dialog.contentTable.add(lidarFPS)
        dialog.contentTable.add(lidar_box)
        dialog.contentTable.row()
        dialog.contentTable.add(memory)
        dialog.contentTable.add(memory_box)
        dialog.contentTable.row()
        dialog.contentTable.add(resolution)
        dialog.contentTable.add(resolution_box)
        dialog.contentTable.row()
        dialog.contentTable.add(compression)
        dialog.contentTable.add(compression_box)
        dialog.contentTable.row()
        dialog.contentTable.add(fixedCamera)
        dialog.contentTable.add(camera_checkbox)
        dialog.contentTable.row()
        dialog.contentTable.add(automaticCamera)
        dialog.contentTable.add(automatic_camera_checkbox)
        dialog.contentTable.row()
        dialog.contentTable.add(distance)
        dialog.contentTable.add(distance_field).width(50f)
        dialog.contentTable.row()
        dialog.contentTable.add(gradualCompression)
        dialog.contentTable.add(gradualBox)
        dialog.contentTable.row()
        dialog.contentTable.add(rotate_label)
        dialog.contentTable.add(rotate_box)
        dialog.contentTable.row()
        dialog.contentTable.add(hide_hud)
        dialog.contentTable.add(hud_box)
        dialog.contentTable.row()

        back_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                updateSpace()
                GameInitializer.space.resume()
                dialog.hide()

            }
        })

        save_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                flushall()
            }
        })

        dialog.contentTable.add(back_button)
        dialog.contentTable.add(save_button)

    }

    fun updateSpace(){
        GameInitializer.space.changeLidarFPS(lidar_box.selected)
        if (resolution_box.selected == "FULLSCREEN") {
            Gdx.graphics.setFullscreenMode(Gdx.graphics.displayMode)
        } else {
            val (wi, hei) = resolution_box.selected.split("x")
            GameInitializer.space.changeResolution(hei.toInt(), wi.toInt())
        }
        GameInitializer.space.switchFixedCamera(camera_checkbox.isChecked)
        GameInitializer.space.switchAutomaticCamera(automatic_camera_checkbox.isChecked)

        GameInitializer.space.compressionLevel.changeCompression(compression_box.selected)
        GameInitializer.space.compressionLevel.switchGradualCompression(gradualBox.isChecked)
        GameInitializer.space.compressionLevel.changeDFCM(distance_field.text.toInt())

        GameInitializer.space.gui.update()
//        println("updating settings")

    }

    fun flushall() {
        prefs.putInteger("LIDAR FPS", lidar_box.selected)
        prefs.putInteger("MEMORY", memory_box.selected)
        prefs.putString("RESOLUTION", resolution_box.selected)
        prefs.putBoolean("FIXED CAMERA", camera_checkbox.isChecked)
        prefs.putBoolean("AUTOMATIC CAMERA", automatic_camera_checkbox.isChecked)

        prefs.putInteger("COMPRESSION", compression_box.selected)
        prefs.putBoolean("GRADUAL COMPRESSION", gradualBox.isChecked)
        prefs.putInteger("DFCM", distance_field.text.toInt())

        prefs.putBoolean("ROTATE", rotate_box.isChecked)
        prefs.putBoolean("HIDE HUD", hud_box.isChecked)

        prefs.flush()

    }
}


class GuiButtons(val space: Space) {
//http://soundbible.com/1705-Click2.html
    private val settings = space.settings

    private val home_button: Image = Image(Texture("Screen3D/home_button.png"))

    private val settings_button: Image = Image(Texture("Screen3D/setting_button.png"))

    private val settings_dialog = settings.dialog
    private val reset_button = Image(Texture("Screen3D/reset_button.png"))

    private val pause_button: Image = Image(Texture("Screen3D/pause_button.png"))
    private val earth_button: Image = Image(Texture("Screen3D/transparent.png"))
    private val arrows_button: Image = Image(Texture("Screen3D/arrows_button.png"))
    private val ff_button: Image = Image(Texture("Screen3D/ff_button.png"))
    private val bf_button: Image = Image(Texture("Screen3D/bf_button.png"))
    val plus = Image(Texture("Screen3D/plus.png"))
    val minus = Image(Texture("Screen3D/minus.png"))

    var rotated = false
    var hidden = false

    val bar = DrawBar(space.stage, space.buffer)

    init {

        space.stage.addActor(minus)
        minus.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                GameInitializer.click.play()
                space.moveBackward(Gdx.graphics.deltaTime)
                space.zoomFixedAway()
            }
        })

        plus.setPosition(minus.x, minus.y + minus.height)
        space.stage.addActor(plus)
        plus.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                GameInitializer.click.play()
                space.moveForward(Gdx.graphics.deltaTime)
                space.zoomFixedCloser()
            }
        })

        space.stage.addActor(bf_button)
        bf_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                GameInitializer.click.play()
                space.skipBackwards10Frames()
            }
        })

        space.stage.addActor(ff_button)
        ff_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                GameInitializer.click.play()
                space.skipForward10frames()
            }
        })

        space.stage.addActor(arrows_button)
        val arrowsLastPos = Vector2(0f, 0f)
        arrows_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                arrowsLastPos.set(x, y)
                GameInitializer.click.play()
            }

            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                arrowsLastPos.set(x, y)
                return super.touchDown(event, x, y, pointer, button)
            }

            override fun touchDragged(event: InputEvent?, x: Float, y: Float, pointer: Int) {
                super.touchDragged(event, x, y, pointer)
                val deltaX = x - arrowsLastPos.x
                val deltaY = y - arrowsLastPos.y
                arrowsLastPos.set(x, y)

                if (deltaX > 0) {
                    space.moveRight(deltaX * 10)
                } else {
                    space.moveLeft(-deltaX * 10)
                }

                if (deltaY > 0) {
                    space.moveUp(deltaY * 10)
                } else {
                    space.moveDown(-deltaY * 10)
                }
            }
        })


        space.stage.addActor(earth_button)
        val earthLastPos = Vector2(0f, 0f)
        earth_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                GameInitializer.click.play()
            }

            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                // Initialize last position
                earthLastPos.set(x, y)
                return super.touchDown(event, x, y, pointer, button)
            }

            override fun touchDragged(event: InputEvent?, x: Float, y: Float, pointer: Int) {
                // Calculate deltas
                val deltaX = x - earthLastPos.x
                val deltaY = y - earthLastPos.y
                // Reset last position
                earthLastPos.set(x, y)

                if (deltaX > 0) {
                    space.rotateRight(deltaX)
                    space.rotateFixedRight(deltaX)
                } else {
                    space.rotateLeft(-deltaX)
                    space.rotateFixedLeft(-deltaX)
                }
                if (deltaY > 0) {
                    space.rotateUp(deltaY)
                    space.moveFixedUp(deltaY)
                } else {
                    space.rotateDown(-deltaY)
                    space.moveFixedDown(-deltaY)
                }
                super.touchDragged(event, x, y, pointer)
            }
        })


        space.stage.addActor(pause_button)
        pause_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                GameInitializer.click.play()
                space.pause.set(!space.pause.get())
            }
        })

        space.stage.addActor(reset_button)
        reset_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                GameInitializer.click.play()
                space.resetCamera()
                space.resetFixed()
            }
        })

        space.stage.addActor(settings_button)
        settings_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                GameInitializer.click.play()
                space.pause()
                settings_dialog.show(space.stage)
            }
        })


        space.stage.addActor(home_button)
        home_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                GameInitializer.click.play()
                GameInitializer.screen = IndexScreen()
            }
        })

        draw()
//        space.stage.isDebugAll = true

    }
    fun draw(){
        minus.setPosition(space.stage.viewport.screenWidth - minus.width, space.stage.viewport.screenHeight * 0.3f)
        plus.setPosition(minus.x, minus.y + minus.height)

        pause_button.setPosition(space.stage.viewport.screenWidth / 2 - (pause_button.width / 2), 50f)
        bf_button.setPosition(pause_button.x - bf_button.width, 50f)
        ff_button.setPosition(pause_button.x + pause_button.width, 50f)

        arrows_button.setPosition(0f, 0f)
        earth_button.setPosition(space.stage.viewport.screenWidth * 0.95f - earth_button.width, space.stage.viewport.screenHeight * (1 / 12f))
        home_button.setPosition(0f, space.stage.viewport.screenHeight - home_button.height)
        settings_button.setPosition(space.stage.viewport.screenWidth - settings_button.width, space.stage.viewport.screenHeight - settings_button.height)
        reset_button.setPosition(settings_button.x, settings_button.y - reset_button.height)

    }

    val images = listOf(
            minus, plus
            ,pause_button, bf_button,ff_button
            ,home_button
            ,earth_button
            ,arrows_button
            ,settings_button,reset_button, bar.bars, bar.button
    )
    fun update(){
        if (!settings.rotate_box.isChecked == rotated) {
            println("rotation/setting mismatch")
            rotated = !rotated
            //mirroring gui

            for (im in images){
                mirror(im)
            }
            bar.reverse = !bar.reverse
        }

        if (!settings.hud_box.isChecked == hidden){
            hideHUD()
        }
    }


    fun hideHUD(){
        images.forEach {it.isVisible = !it.isVisible}
        hidden = !hidden
        settings.hud_box.isChecked = hidden
        settings_button.isVisible = true
    }
}


fun mirror(im: Image) {
    im.setOrigin(im.width/2f, im.height/2)
    im.rotateBy(180f)
    println("${im.x}, ${im.y}, ${im.isVisible}")

    if ( im.x < Gdx.graphics.width/2) {
        im.x = Gdx.graphics.width/2 + (Gdx.graphics.width/2 - im.x) - im.width
    } else {
        im.x = Gdx.graphics.width/2 - (im.x - Gdx.graphics.width/2) - im.width
    }

    if ( im.y < Gdx.graphics.height/2) {
        im.y = Gdx.graphics.height/2 + (Gdx.graphics.height/2 - im.y) - im.height
    } else {
        im.y = Gdx.graphics.height/2 - (im.y - Gdx.graphics.height/2) - im.height
    }
}

/**
 * interface meant to make changing the bottom bar
 * easier. However, not elaborated on more as the second
 * try seemed to work well enough.
 */
interface Bar {
    fun rotate()
    fun update()
    fun up()
}

/**
*
*/
class DrawBar(stage: Stage, val buffer: Buffer? = null): Bar{
    val button = Image(Texture("Screen3D/slider_button.png"))
    val bars = Image(Texture("Screen3D/middle_bar.png"))

    var left_bound: Float
    var right_bound: Float



    init {
//        println("drawbar called")
        bars.width = Gdx.graphics.width*0.5f
        bars.setPosition(Gdx.graphics.width*0.25f, 10f)
        left_bound = bars.x - button.width / 2
        right_bound = bars.x + bars.width - button.width / 2
        stage.addActor(bars)
        stage.addActor(button)
        button.addListener(object : DragListener() {
            override fun drag(event: InputEvent?, x: Float, y: Float, pointer: Int) {
                val new_center = this@DrawBar.button.x - this@DrawBar.button.width / 2 + x
                if (new_center < left_bound || new_center > right_bound) {
                    println("out of bars")
                } else {
                    this@DrawBar.button.moveBy(x - this@DrawBar.button.width / 2, 0f);
                }
            }

            override fun dragStop(event: InputEvent?, x: Float, y: Float, pointer: Int) {
                buffer!!
                println("dropped, $x, $y, ${this@DrawBar.button.x}, ${this@DrawBar.button.y}")
                var progress = this@DrawBar.button.x + this@DrawBar.button.width / 2 // current center button
                progress = (progress - left_bound) / (right_bound - left_bound)
                buffer.skipTo(progress)
            }
        })

        button.setPosition(left_bound, bars.y + bars.height / 2 - button.height/2)
        button.toFront()
    }



    var reverse = false

    override fun update(){
        buffer!!
        if  (button.listeners.first() is DragListener && !(button.listeners.first() as DragListener).isDragging) {
            var percentage = buffer.progress
            if (reverse) {
                percentage = 1 - percentage
            }
            val newX =  percentage * (right_bound - left_bound) - button.width/2 + bars.x
            button.setPosition(newX, button.y)
        } else {
            if (!(button.listeners.first() as DragListener).isDragging) {
                println("problem")
            }
        }

    }

    override fun up(){
        button.setPosition(button.x+1, button.y)
    }

    override fun rotate() {
        mirror(bars)
    }
}

