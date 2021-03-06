package org.quokka.kotlin.internals

import com.badlogic.gdx.Gdx
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.ByteArrayInputStream
import java.lang.Exception
import java.nio.ByteBuffer
import java.sql.*
import kotlin.system.exitProcess

/**
 * Singleton object which manages multiple database connections.
 */
object DbConnectionPool {
    private val config: HikariConfig = HikariConfig()
    private lateinit var ds: HikariDataSource
    private var setup = false

    /**
     * Initiate the database connection. This should not be done statically as the user may want to change these properties.
     *
     * @param url The url of the database. Should be in 'jdbc:postgresql://${address}/${name}' format
     * @param username The username to login with.
     * @param password Password associated with the username.
     */
    fun setup(url: String = "jdbc:postgresql://localhost/lidar", username: String = "lidar", password: String = "mindhash") {
        if (setup)
            return
        config.jdbcUrl = url
        config.username = username
        config.password = password
        config.addDataSourceProperty("cachePrepStmts", "true")
        config.addDataSourceProperty("prepStmtCacheSize", "250")
        config.addDataSourceProperty("prepStmtSqlLimit", "2048")
        try {
            println("Initializing database ${username}@${url}")
            ds = HikariDataSource(config)
        } catch (e: Exception) {
            Gdx.app?.let {
                it.error("Database", "No connection to database, shutting down.")
                it.exit()
            }
            println("No connection to database")
            exitProcess(-1)
        }
    }

    /**
     * Property to retrieve a new unused connection.
     */
    val connection: Connection
        get() = ds.connection
}

/**
 * The database class is used to communicate with the backend database which provides lidar recordings.
 *
 * First connect to the database and then use the appropriate methods to insert/retrieve data.
 *
 * Most important methods: connect, recordings, getFrames, recordingFromFile
 *
 * Example for setting up the schema.
 * <pre>
 * {@code
 * val db = Database()
 * db.connect("nyx", "lidar")
 * db.initTables()
 * db.close()
 * }
 * </pre>
 *
 * Example for fetching 100 frames at a framerate of 10 fps from each recording in the database.
 * <pre>
 * {@code
 * val db = Database()
 * db.connect("nyx", "lidar")
 * db.recordings.forEach {
 *     println("$it")
 *     val fr = db.getFrames(it.id, 1638, 100, framerate = Framerate.TEN)
 *     fr.forEach { f ->
 *         println("[${f.frameId}]: ${f.coords.size} ${f.coords.take(3)}")
 *     }
 * }
 * db.close()
 * }
 * </pre>
 *
 * Example of uploading a file to the database as a recording.
 * <pre>
 * {@code
 * val db = Database()
 * db.connect("nyx", "lidar")
 * db.recordingFromFile(path = "/path/to/file.bag", title = "example title")
 * db.close()
 * }
 */
object Database {
    private const val CREATE_DB_QUERY = """
CREATE TABLE IF NOT EXISTS recording (id SERIAL PRIMARY KEY, title varchar(255),
 minframe integer DEFAULT 0,
 maxframe integer DEFAULT 0,
 fps integer DEFAULT 10,
 maxpoints integer
);
 CREATE TABLE IF NOT EXISTS frame (frameid integer, recid integer REFERENCES recording(id)
 ON DELETE CASCADE, points bytea, PRIMARY KEY (frameid, recid));
"""
    private const val DELETE_DB_QUERY = "DROP TABLE IF EXISTS frame CASCADE; DROP TABLE IF EXISTS recording CASCADE;"
    private const val INSERT_FRAME = "INSERT INTO frame (frameid, recid, points) VALUES (?, ?, ?);"
    private const val INSERT_RECORDING = "INSERT INTO recording (title) VALUES (?) RETURNING id;"
    private const val SELECT_RECORDINGS = """
SELECT minframe, maxframe, id, title, COUNT(frameid) as numberofframes, fps, maxpoints FROM recording,
 frame WHERE recid = id GROUP BY id;
"""
    private const val SELECT_SINGLE_RECORDING = "SELECT * FROM recording WHERE id = ?;"
    private const val SELECT_POINTS = "SELECT frameid, points FROM frame WHERE frameid = ANY (?) AND recid = ? ORDER BY frameid ASC LIMIT ?;"
    private const val SELECT_RECORDING_FPS = "SELECT fps FROM recording WHERE id = ?;"
    private const val UPDATE_RECORDING_FRAMES = "UPDATE recording SET minframe = ?, maxframe = ?, maxpoints = ? WHERE id = ?;"
    private const val FLOAT_BYTE_SIZE = 4
    private const val FLOATS_PER_POINT = 3

    /**
     * Returns a list of all recordings and their meta data.
     * Refer to the RecordingMeta class for more info on fields and data about recordings.
     */
    val recordings: List<RecordingMeta>
        get() {
            val conn = DbConnectionPool.connection
            val st = conn.prepareStatement(SELECT_RECORDINGS)
            val rs = st.executeQuery()
            val recs: MutableList<RecordingMeta> = mutableListOf()

            while (rs.next()) {
                recs.add(
                        RecordingMeta(
                                rs.getInt("id"),
                                rs.getString("title"),
                                rs.getInt("minframe"),
                                rs.getInt("maxframe"),
                                rs.getInt("numberofframes"),
                                rs.getInt("fps"),
                                rs.getInt("maxpoints")
                        )
                )
            }
            conn.close()
            return recs
        }

    /**
     * Retrieve the meta data for a recording based on its ID.
     *
     * @param id The id associated with a recording.
     * @return Meta data about the recording.
     */
    fun getRecording(id: Int): RecordingMeta? {
        val conn = DbConnectionPool.connection
        val st = conn.prepareStatement(SELECT_SINGLE_RECORDING).apply { setInt(1, id) }
        val rs = st.executeQuery()
        var ret: RecordingMeta? = null
        if (rs.next()) {
            ret = RecordingMeta(
                    rs.getInt("id"),
                    rs.getString("title"),
                    rs.getInt("minframe"),
                    rs.getInt("maxframe"),
                    rs.getInt("maxframe") - rs.getInt("minframe"),
                    rs.getInt("fps"),
                    rs.getInt("maxpoints")
            )
        }
        rs.close()
        st.close()
        conn.close()
        return ret
    }

    /**
     * Construct the schema.
     */
    fun initTables() {
        val conn = DbConnectionPool.connection
        val st = conn.prepareStatement(CREATE_DB_QUERY)
        st.executeUpdate()
        st.close()
        conn.close()
    }

    /**
     * Deconstruct the schema.
     */
    fun destroyTables() {
        val conn = DbConnectionPool.connection
        val st = conn.prepareStatement(DELETE_DB_QUERY)
        st.executeUpdate()
        st.close()
        conn.close()
    }

    /**
     * Creates a new recording without any frames.
     *
     * @param title The title of the recording.
     * @return The id given to the recording for inserting frames later. Returns -1 if the recording could not be
     * created.
     */
    private fun newRecording(title: String): Int {
        val conn = DbConnectionPool.connection
        val st = conn.prepareStatement(INSERT_RECORDING).apply { setString(1, title) }
        val rs = st.executeQuery()
        var r = -1
        while (rs.next()) {
            r = rs.getInt(1)
        }

        rs.close()
        st.close()
        conn.close()
        return r
    }

    /**
     * Insert an array of array of floats as a frame.
     *
     * @param frameId The id of the frame.
     * @param recordingId The id of the recording the frame should belong to.
     * @param points The raw point data. The array format has to be Double[3][].
     * @return Returns the minimum and maximum Z coordinate
     */
    private fun insertRawPointsAsFrame(frameId: Int, recordingId: Int, points: Array<Array<Float>>) {
        val conn = DbConnectionPool.connection

        // Allocate a byte buffer to put the floats in which is then put into the database as bytea
        val bb = ByteBuffer.allocate(FLOAT_BYTE_SIZE * points.size * FLOATS_PER_POINT)
        points.forEach {
            bb.putFloat(it[0])
            bb.putFloat(it[1])
            bb.putFloat(it[2])
        }

        val st = conn.prepareStatement(INSERT_FRAME).apply {
            setInt(1, frameId)
            setInt(2, recordingId)
            setBinaryStream(3, ByteArrayInputStream(bb.array()))
            executeUpdate()
        }
        st.close()
        conn.close()
    }


    /**
     * Record all data from a file and put it in the database with a title for the given recording.
     *
     * @param path Path to the .bag file with the data.
     * @param title Title of the recording in the database.
     * @param reader The reader which parses the lidar data. Defaults to the default reader.
     * @param filterFun A filter function which removes LidarCoords from the uploaded set.
     */
    fun recordingFromFile(
            path: String,
            title: String,
            reader: LidarReader = LidarReader(),
            filterFun: (LidarCoord) -> Boolean = { true }
    ) {
        val conn = DbConnectionPool.connection
        // Create a new recording first
        val recId = newRecording(title)
        // Id of the frame that is being modified.
        var lastFrame = -1
        // The list which is used to construct the raw point data for a frame.
        var currPoints: MutableList<Array<Float>>? = null
        var minFrame = Integer.MAX_VALUE
        var maxFrame = Integer.MIN_VALUE
        var maxPoints = 0
        // Iterate through Azimuth blocks and construct and insert frames into the database.
        reader.readAzimuthBlocks(path, { az ->
            val fid = az.frameId.toInt()

            if (fid > maxFrame)
                maxFrame = fid
            if (fid < minFrame)
                minFrame = fid

            if (fid > lastFrame) {
                // If the frameId changes a whole frame has been captured since the frames in the file are ordered
                if (currPoints != null) {
                    // If the last frame existed, insert it into the database.
                    println("Inserted frame $lastFrame with ${currPoints!!.size} points")
                    currPoints?.let {
                        if (it.isNotEmpty()) {
                            if (maxPoints < it.size) {
                                maxPoints = it.size
                            }
                            insertRawPointsAsFrame(lastFrame, recId, it.toTypedArray())
                        }
                    }
                }

                // Update the frame id that is being constructed and create a new list of coords.
                lastFrame = fid
                currPoints = reader.azimuthBlockToLidarCoords(az).filter(filterFun).map {
                    arrayOf(it.x, it.y, it.z)
                }.toMutableList()
            } else if (fid == lastFrame) {
                // If the current frame is being constructed then just add the points to that.
                currPoints!!.addAll(reader.azimuthBlockToLidarCoords(az).filter(filterFun).map {
                    arrayOf(it.x, it.y, it.z)
                })
            }
        })

        // Insert last frame
        currPoints?.let {
            if (it.isNotEmpty()) {
                if (maxPoints < it.size) {
                    maxPoints = it.size
                }
                insertRawPointsAsFrame(lastFrame, recId, it.toTypedArray())
            }
        }

        // Update the meta data of the recording
        val st = conn.prepareStatement(UPDATE_RECORDING_FRAMES).apply {
            setInt(1, minFrame)
            setInt(2, maxFrame)
            setInt(3, maxPoints)
            setInt(4, recId)
            executeUpdate()
        }
        st.close()
        conn.close()
    }

    /**
     * Retrieve a list of frames from the database with the selected filter criteria.
     * The list is at most as long as the numberOfFrames given.
     * If frames are missing in the data the list will be shorter and frames will be skipped.
     *
     * @param recordingId The id of the recording where the frames should be from.
     * @param startFrame The starting frame of the buffer.
     * @param numberOfFrames The number of frames to be captured.
     * @param framerate How many frames should be displayed in a second.
     * @return A list of the constructed LidarFrames.
     */
    fun getFrames(
            recordingId: Int,
            startFrame: Int,
            numberOfFrames: Int,
            framerate: Int
    ): List<LidarFrame> {
        val conn = DbConnectionPool.connection

        var spf = 1
        val fpsStx = conn.prepareStatement(SELECT_RECORDING_FPS)
        fpsStx.setInt(1, recordingId)
        val fpsRsx = fpsStx.executeQuery()
        while (fpsRsx.next()) {
            val footageFps = fpsRsx.getInt("fps")
            spf = if (footageFps / framerate != 0) {
                footageFps / framerate
            } else {
                1
            }
        }
        val frames = mutableListOf<LidarFrame>()

        val recording = getRecording(recordingId)
        if (recording == null) {
            return emptyList()
        }

        // Create an array of all frame ids to be fetched
        val frameIds = (startFrame until (startFrame + spf * numberOfFrames) step spf).toList().toTypedArray()

        val stx = conn.prepareStatement(SELECT_POINTS).apply {
            setArray(1, conn.createArrayOf("integer", frameIds))
            setInt(2, recordingId)
            setInt(3, numberOfFrames)
        }

        // Iterate over result sets and parse each frame back to LidarFrame objects
        val rsx = stx.executeQuery()
        while (rsx.next()) {
            val points = rsx.getBinaryStream("points").buffered()
            val buff = ByteArray(FLOAT_BYTE_SIZE * FLOATS_PER_POINT)
            val off = 0
            val frameList = mutableListOf<LidarCoord>()
            while (points.read(buff, off, buff.size) == buff.size) {
                val bb = ByteBuffer.wrap(buff)
                frameList.add(LidarCoord(bb.float, bb.float, bb.float))
            }

            frames.add(LidarFrame(
                    rsx.getInt("frameid"),
                    frameList.toList()
            ))
        }

        rsx.close()
        stx.close()
        conn.close()
        return frames
    }
}


/**
 * Meta data belonging to a recording.
 *
 * @property id The unique ID in the table associated with the recording.
 * @property title The title of the recording.
 * @property minFrame The minimum frame value.
 * @property maxFrame The maximum frame value.
 * @property numberOfFrames The total number of frames.
 * @property maxNumberOfPoints The maximum number of points in a single frame.
 */
data class RecordingMeta(
        val id: Int,
        val title: String,
        val minFrame: Int,
        val maxFrame: Int,
        val numberOfFrames: Int,
        val fps: Int,
        val maxNumberOfPoints: Int
)
