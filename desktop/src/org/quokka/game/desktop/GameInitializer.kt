package org.quokka.game.desktop

import com.badlogic.gdx.Game
import com.badlogic.gdx.graphics.g2d.BitmapFont

import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.mygdx.game.desktop.Space
import org.quokka.kotlin.Enviroment.Settings


class GameInitializer : Game() {
    lateinit var batch: SpriteBatch
    lateinit var font: BitmapFont
    lateinit var game : Space
    lateinit var settings : Settings

    override fun create() {
        batch = SpriteBatch()
        font = BitmapFont() //
        //Use LibGDX's default Arial font.
        game = Space()
        settings = Settings(game)
        this.setScreen(game)
    }

    override fun render() {
        super.render() //important!
    }

//    override fun pause() {
//        TODO("Not yet implemented")
//    }
//
//    override fun resume() {
//        TODO("Not yet implemented")
//    }
//
//    override fun resize(width: Int, height: Int) {
//        TODO("Not yet implemented")
//    }

    override fun dispose() {
        batch!!.dispose()
        font!!.dispose()
    }
}
