package org.quokka.kotlin.Enviroment

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.mygdx.game.desktop.Space

class Settings(space: Space) {
    val font = BitmapFont()
    val skin = Skin(Gdx.files.internal("Skins/glassy-ui.json"))

    val shared_style = Label.LabelStyle(font, Color.WHITE)

    // labels
    val prefs = Gdx.app.getPreferences("My Preferences")

    val lidarFPS = Label("LIDAR FPS", shared_style)
    val lidar_box = SelectBox<Int>(skin)


    val playbackFPS = Label("PLAYBACK FPS", shared_style)
    val playback_slider = Slider(1f, 60f, 5f, false, skin)


    val memory = Label("MEMORY", shared_style)

    val resolution = Label("RESOLUTION", shared_style)
    val resolution_box = SelectBox<String>(skin)


    val compression = Label("COMPRESSION", shared_style)
    val compression_box = SelectBox<Int>(skin)


    val gradualCompression = Label("GRADUAL COMPRESSION", shared_style)
    val gradualBox = CheckBox("", skin)


    val distance = Label("DISTANCE", shared_style)
    val distance_field = TextField("", skin) // Todo: where was this for

    val fixedCamera = Label("FIXED CAMERA", shared_style)
    val camera_checkbox = CheckBox("", skin)

    val back_button = TextButton("BACK", skin)
    val save_button = TextButton("SAVE", skin)


    val dialog = Dialog("", skin)

    init {
        lidar_box.setItems(1, 2, 5, 10, 12, 20)
        lidar_box.selected = prefs.getInteger("LIDAR FPS", 12)
        playback_slider.value = prefs.getFloat("PLAYBACK FPS", 0f)
        resolution_box.setItems("1920x1080", "1080x720")
        resolution_box.selected = prefs.getString("RESOLUTION", "1080x720")
        compression_box.setItems(1, 2, 3, 4)
        compression_box.selected = prefs.getInteger("COMPRESSION", 4)
        gradualBox.isChecked = prefs.getBoolean("GRADUAL COMPRESSION", false)


        dialog.setSize(200f, 250f)
        dialog.setPosition(Gdx.graphics.width / 2 - 100f, Gdx.graphics.height / 2 - 101f)
        dialog.contentTable.defaults().pad(10f)
        dialog.color = Color(Color.GRAY.r, Color.GRAY.g, Color.GRAY.b, 1f)


        dialog.contentTable.add(Label("PREFERENCES", shared_style))
        dialog.contentTable.row()

        dialog.contentTable.add(lidarFPS)
        dialog.contentTable.add(lidar_box)
        dialog.contentTable.row()
        dialog.contentTable.add(playbackFPS)
        dialog.contentTable.add(playback_slider)
        dialog.contentTable.row()
        dialog.contentTable.add(memory)
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
        dialog.contentTable.add(distance)
        dialog.contentTable.add(distance_field).width(50f)
        dialog.contentTable.row()
        dialog.contentTable.add(gradualCompression)
        dialog.contentTable.add(gradualBox)
        dialog.contentTable.row()

        back_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                println("quit settings menu")
                space.pause.set(true)
                dialog.hide()

            }
        })

        save_button.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                println("saved settings (MOCK)")
                flushall()
            }
        })

        dialog.contentTable.add(back_button)
        dialog.contentTable.add(save_button)

    }

    private fun flushall() {
        prefs.putInteger("LIDAR FPS", lidar_box.selected )
        prefs.putFloat("PLAYBACK FPS", playback_slider.value)
        prefs.putString("RESOLUTION", resolution_box.selected)
        prefs.putInteger("COMPRESSION", compression_box.selected)
        prefs.putBoolean("GRADUAL COMPRESSION", gradualBox.isChecked)
        prefs.flush()

    }
}

fun GuiButtons(space: Space) {
    val settings = Settings(space)
    var bf_button: Image = Image(Texture("Screen3D/bf_button.png"))
    bf_button.setPosition(Gdx.graphics.width / 2 - 175.toFloat(), 0f)
    space.stage!!.addActor(bf_button)
    bf_button.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent, x: Float, y: Float) {
            println("clicked BF")
        }
    })

    var ff_button: Image = Image(Texture("Screen3D/ff_button.png"))
    ff_button.setPosition(Gdx.graphics.width / 2 + 75.toFloat(), 0f)
    space.stage!!.addActor(ff_button)
    ff_button.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent, x: Float, y: Float) {
            println("clicked FF")
        }
    })

    var arrows_button: Image = Image(Texture("Screen3D/arrows_button.png"))
    arrows_button.setPosition(Gdx.graphics.width - 251.toFloat(), 0f)
    space.stage!!.addActor(arrows_button)
    arrows_button.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent?, x: Float, y: Float) {
            val x1 = arrows_button.width / 3
            val x2 = 2 * arrows_button.width / 3
            val x3 = arrows_button.width

            val y1 = arrows_button.height / 3
            val y2 = 2 * arrows_button.height / 3
            val y3 = arrows_button.height

            val delta = Gdx.graphics.deltaTime
            when {
                x < x1 && y < y1 -> println("q1")
                x < x2 && y < y1 -> space.rotateDown(delta)
                x < x3 && y < y1 -> println("q3")

                x < x1 && y < y2 -> space.rotateLeft(delta)
                x < x2 && y < y2 -> println("center")
                x < x3 && y < y2 -> space.rotateRight(delta)

                x < x1 && y < y3 -> println("q7")
                x < x2 && y < y3 -> space.rotateUp(delta)
                x < x3 && y < y3 -> println("q9")

            }
        }
    })


    var earth_button: Image = Image(Texture("Screen3D/earth_button.png"))
    earth_button.setPosition(Gdx.graphics.width - 185.toFloat(), 60f)
    space.stage!!.addActor(earth_button)
    earth_button.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent?, x: Float, y: Float) {
            println("earth clicked")
        }
    })


    var pause_button: Image = Image(Texture("Screen3D/pause_button.png"))
    pause_button.setPosition(Gdx.graphics.width / 2 - 50.toFloat(), 0f)
    space.stage!!.addActor(pause_button)
    pause_button.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent, x: Float, y: Float) {
            println("clicked PAUSE")
            space.pause()
        }
    })

    var reset_button: Image = Image(Texture("Screen3D/reset_button.png"))
    reset_button.setPosition(Gdx.graphics.width - 110.toFloat(), Gdx.graphics.height - 251.toFloat())
    space.stage!!.addActor(reset_button)
    reset_button.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent, x: Float, y: Float) {
            println("clicked RESET")
            space.resetCamera()
        }
    })

    val settings_dialog = settings.dialog

    var settings_button: Image = Image(Texture("Screen3D/setting_button.png"))
    settings_button.setPosition(Gdx.graphics.width - 110.toFloat(), Gdx.graphics.height - 101.toFloat())
    space.stage!!.addActor(settings_button)
    settings_button.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent, x: Float, y: Float) {
            println("clicked SETTINGS and opened settings")
            space.pause.set(false)
            settings_dialog.show(space.stage)

        }
    })


    var home_button: Image = Image(Texture("Screen3D/home_button.png"))
    home_button.setPosition(0.toFloat(), Gdx.graphics.height - 101.toFloat())
    space.stage!!.addActor(home_button)
    home_button.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent, x: Float, y: Float) {
            println("clicked HOME")
        }
    })


    fun changLidarFPS(newLFPS: Int) {
        space.lidarFPS = newLFPS
        space.newLidaarFPS.set(true)
    }

    fun changePlaybackFPS(newFPS: Int) {
        space.playbackFPS = newFPS
    }

    fun changeResolution(height: Int, width: Int) {
        space.changeResolution(height, width)
    }

    fun switchFixedCamera(fixed: Boolean) {
        space.fixedCamera = fixed
    }


}