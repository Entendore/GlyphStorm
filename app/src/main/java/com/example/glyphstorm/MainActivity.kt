package com.example.glyphstorm

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.InterstitialAd
import com.google.android.gms.ads.InterstitialAdLoadCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// ══════════════════════ AD IDS (substituted from CONFIG at build) ══════════════════════

object AdIds {
    const val APP = "ca-app-pub-3940256099942544~3347511713"
    const val BANNER = "ca-app-pub-3940256099942544/6300978111"
    const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
    const val INTER = "ca-app-pub-3940256099942544/1033173712"
}

// the game only talks to this interface — swap networks by re-implementing
interface AdBridge {
    fun showBanner(show: Boolean)
    fun hasRewarded(): Boolean
    fun showRewarded(onReward: () -> Unit, onClosed: () -> Unit)
    fun maybeInterstitial()
}

// ══════════════════════════════ ACTIVITY ══════════════════════════════

class MainActivity : Activity() {
    private var gv: GameView? = null
    private lateinit var adMgr: AdManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 28)
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        adMgr = AdManager(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(this)
        gv = GameView(this, adMgr)
        frame.addView(gv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val adBox = LinearLayout(this)
        root.addView(adBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        @Suppress("DEPRECATION")
        gv?.setOnApplyWindowInsetsListener { v, ins ->
            (v as GameView).onInsets(ins.systemWindowInsetTop, ins.systemWindowInsetBottom,
                ins.systemWindowInsetLeft, ins.systemWindowInsetRight)
            ins
        }
        gv?.requestApplyInsets()

        adMgr.init(adBox) // SDK init + first loads (async, game is playable immediately)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else gv?.autoPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (gv?.onBack() != true) super.onBackPressed()
    }
}

// ══════════════════════════════ AD MANAGER ══════════════════════════════

class AdManager(private val act: Activity) : AdBridge {
    private var banner: AdView? = null
    private var rewarded: RewardedAd? = null
    private var inter: InterstitialAd? = null
    private var rewardCb: (() -> Unit)? = null
    private var closedCb: (() -> Unit)? = null

    fun init(container: LinearLayout) {
        MobileAds.initialize(act) {}
        banner = AdView(act).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = AdIds.BANNER
            container.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            loadAd(AdRequest.Builder().build())
        }
        loadRewarded(); loadInter()
    }

    override fun showBanner(show: Boolean) {
        val b = banner ?: return
        val vis = b.visibility == View.VISIBLE
        if (vis == show) return
        b.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun loadRewarded() {
        RewardedAd.load(act, AdIds.REWARDED, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(e: LoadAdError) { rewarded = null }
                override fun onAdLoaded(ad: RewardedAd) {
                    rewarded = ad
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            loadRewarded()
                            val cb = closedCb; closedCb = null; cb?.invoke()
                        }
                        override fun onAdFailedToShowFullScreenContent(e: AdError) {
                            val cb = closedCb; closedCb = null; cb?.invoke()
                        }
                    }
                }
            })
    }

    override fun hasRewarded() = rewarded != null

    override fun showRewarded(onReward: () -> Unit, onClosed: () -> Unit) {
        val r = rewarded
        if (r == null) { onClosed(); return }
        rewardCb = onReward; closedCb = onClosed
        r.show(act) { rewardCb?.invoke() }
    }

    fun loadInter() {
        InterstitialAd.load(act, AdIds.INTER, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(e: LoadAdError) { inter = null }
                override fun onAdLoaded(ad: InterstitialAd) { inter = ad }
            })
    }

    override fun maybeInterstitial() {
        inter?.show(act)
        inter = null
        loadInter()
    }
}

// ═══════════════════════════════ ENTITIES ═══════════════════════════════

class Bullet(var x: Float, var y: Float, var vx: Float, var vy: Float, var dmg: Float) {
    var r = 9f; var glyph = "✦"; var pierce = 0; var homing = 0f
    var life = 2.4f; var crit = false
    var lance = false; var split = false; var mini = false; var star = false
    var boom = false; var boomT = 0f; var ret = false
    val hit = HashSet<Enemy>()
}

class EBullet(var x: Float, var y: Float, var vx: Float, var vy: Float, var dmg: Float) {
    var r = 8f; var glyph = "●"; var color = Color.rgb(255, 80, 80)
    var life = 9f; var grazed = false
    var pop = -1f; var popN = 0
}

class Enemy(var x: Float, var y: Float) {
    var r = 22f; var hp = 10f; var maxHp = 10f; var speed = 70f; var dmg = 12f
    var glyph = "👾"; var kind = 0
    var xp = 1f; var fireTimer = 0f
    var wobble = Random.nextFloat() * 6.28f; var flash = 0f
    var pattern = 0; var phase = 0f; var spiralA = 0f
    var slowT = 0f; var freezeT = 0f
    var dashX = 0f; var dashY = 0f
    var affix = -1; var vari = 0
    var sweep = 1f; var spin = 70
    var dead = false
}

class Gem(var x: Float, var y: Float, var value: Float) { var t = Random.nextFloat() * 6f }
class Heart(var x: Float, var y: Float) { var t = Random.nextFloat() * 6f }
class Chest(var x: Float, var y: Float) { var t = Random.nextFloat() * 6f }
class Power(val kind: Int, var x: Float, var y: Float) { var t = Random.nextFloat() * 6f }
class Mine(var x: Float, var y: Float, val dmg: Float, val rad: Float) {
    var arm = 0.45f; var fuse = 7f
}

class Meteor(val tx: Float, val ty: Float, val dur: Float, val dmg: Float, val rad: Float) {
    var t = 0f
    fun drawY() = ty - (1f - t / dur) * 520f
}

class FireZone(var x: Float, var y: Float, val r: Float, val dps: Float, val dur: Float,
               val poison: Boolean = false) { var t = 0f }

class Bolt(x1: Float, y1: Float, x2: Float, y2: Float) {
    val pts: FloatArray
    var life = 0.22f
    init {
        val n = 7
        pts = FloatArray((n + 1) * 2)
        val dx = x2 - x1; val dy = y2 - y1
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / len; val ny = dx / len
        for (i in 0..n) {
            val f = i / n.toFloat()
            val j = if (i == 0 || i == n) 0f else (Random.nextFloat() - 0.5f) * len * 0.18f
            pts[i * 2] = x1 + dx * f + nx * j
            pts[i * 2 + 1] = y1 + dy * f + ny * j
        }
    }
}

class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float,
               var glyph: String, var size: Float, var color: Int) { var maxLife = life }

class FloatText(var x: Float, var y: Float, var text: String, var color: Int, var size: Float) {
    var life = 0.9f
}

class Ring(var x: Float, var y: Float, val maxR: Float, val color: Int) { var r = 40f; var life = 0.45f }
class Star(var x: Float, var y: Float, var glyph: String, var phase: Float)

class Player {
    var x = 0f; var y = 0f; var r = 24f
    var hp = 100f; var baseMaxHp = 100f; var maxHp = 100f; var speed = 330f
    var level = 1; var xp = 0f; var xpNext = 6f
    var fireRate = 1.7f; var damage = 12f; var bulletSpeed = 560f
    var bulletCount = 1; var pierce = 0; var bulletSize = 1f; var homing = 0f
    var magnet = 130f; var critChance = 0.05f; var critMult = 2f
    var lifesteal = 0f; var regen = 0f
    var shield = 0; var shieldMax = 0; var shieldTimer = 22f
    var rearGun = false; var sideGuns = 0; var luck = 1f
    var nova = 0; var novaTimer = 5.5f
    var fireTimer = 0f; var invuln = 0f; var hurtFlash = 0f
    var armorPts = 0f; var dodgePct = 0f; var thorns = 0f; var regenEq = 0f
    var mightEq = 0f; var hasteEq = 0f; var luckEq = 0f; var magnetEq = 0f
    var hornetTimer = 2.5f; var meteorTimer = 3f; var frostTimer = 2.5f
    var thunderTimer = 2f; var shardTimer = 0f; var boomTimer = 2f; var mineTimer = 2f
    var vacuumT = 0f; var tailwindT = 0f
    var aimA = 0f
}

// ════════════ ARMOR / UPGRADE / WEAPON / META / ACHIEVEMENT CLASSES ════════════

class AffixTpl(val id: String, val icon: String, val base: Float, val isPct: Boolean, val label: String)
class ArmorPiece(val slot: Int, val rarity: Int, val affixes: HashMap<String, Float>) {
    val score = rarity * 10f + affixes.size * 4f + affixes.values.sum()
}
class Upgrade(val id: String, val title: String, val icon: String, val desc: String,
              val rarity: Int, val kind: Int, val sub: String, val apply: () -> Unit)
class Tpl(val id: String, val icon: String, val name: String, val max: Int,
          val desc: (Player, Float) -> String, val apply: (Player, Float) -> Unit,
          val avail: (Player) -> Boolean = { true })
class WeaponTpl(val id: String, val icon: String, val name: String, val maxLvl: Int,
                val pairId: String, val pairIcon: String, val pairReq: Int,
                val evoIcon: String, val evoName: String, val evoDesc: String,
                val pName: String, val pDesc: String,
                val lvlDesc: (Int) -> String)
class MetaPerk(val id: String, val icon: String, val name: String, val desc: String,
               val maxLvl: Int = 5)
class Achv(val id: String, val icon: String, val name: String, val desc: String)

// ═══════════════════════════════ THE GAME ═══════════════════════════════

class GameView(context: Context, val ads: AdBridge? = null) : View(context) {

    enum class State { MENU, STARTER, PLAY, PAUSE, LEVELUP, ARMOR, CODEX, OVER }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val ds = context.resources.displayMetrics.density

    // ── production: safe-area, input, perf ──
    var safeTop = 0f; var safeBottom = 0f
    var safeLeft = 0f; var safeRight = 0f
    var joyPointer = -1
    var restoring = false
    val contRect = RectF()
    val perfRect = RectF()

    fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        safeTop = top / ds; safeBottom = bottom / ds
        safeLeft = left / ds; safeRight = right / ds
    }

    // ── persistence ──
    private val prefs = context.getSharedPreferences("glyphstorm", Context.MODE_PRIVATE)
    companion object { private const val SAVE_VERSION = 2 }
    init {
        if (prefs.getInt("save_v", 0) < SAVE_VERSION)
            prefs.edit().putInt("save_v", SAVE_VERSION).apply() // migrations branch on old version here
    }
    private var best = prefs.getFloat("best", 0f)
    private var bestScore = prefs.getInt("bestScore", 0)
    private var shards = prefs.getInt("shards", 0)
    private var totalKills = prefs.getInt("tkills", 0)
    private var deaths = prefs.getInt("deaths", 0)
    private var diff = prefs.getInt("diff", 1)
    private var soundOn = prefs.getBoolean("sound", true)
    private var musicOn = prefs.getBoolean("music", true)
    private var fxOn = prefs.getBoolean("fx", true)
    private var perfMode = prefs.getBoolean("perf", false)
    private val discovered = HashSet(prefs.getStringSet("evo", emptySet()) ?: emptySet())
    private val achvUnlocked = HashSet(prefs.getStringSet("achv", emptySet()) ?: emptySet())
    private val metaLv = HashMap<String, Int>().apply {
        for (k in listOf("hp", "dmg", "mag", "arm", "insight", "ars", "ban"))
            put(k, prefs.getInt("meta_$k", 0))
    }
    private var sessionStart = SystemClock.elapsedRealtime()

    // ── seeded rng (daily mode) ──
    private var rng: Random = Random(System.nanoTime())
    var dailyMode = false

    // ── audio ──
    @Volatile private var sp: SoundPool? = null
    private val sfxIds = ConcurrentHashMap<String, Int>()
    private val sfxCnt = HashMap<String, Int>()
    private var musicId = -1; private var musicStream = 0; private var musicReady = false
    private var musicRate = 1f; private var curMusicRate = 0f
    @Volatile private var focusLost = false
    private var focusRequested = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { fc ->
        focusLost = fc == AudioManager.AUDIOFOCUS_LOSS ||
            fc == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            fc == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
    }

    private val TAU = (PI * 2).toFloat()
    private val PIf = PI.toFloat()
    private val CYAN = Color.rgb(160, 225, 255)
    private val GOLD = Color.rgb(255, 213, 79)
    private val RED = Color.rgb(255, 90, 90)
    private val ORANGE = Color.rgb(255, 150, 60)
    private val ICE = Color.rgb(150, 220, 255)
    private val GREEN = Color.rgb(110, 235, 110)
    private val MAGENTA = Color.rgb(255, 100, 200)
    private val STORM = Color.rgb(150, 190, 255)
    private val VOID = Color.rgb(200, 80, 255)

    private val RARITY_MULT = floatArrayOf(1f, 1.5f, 2.2f, 3.2f)
    private val RARITY_PREFIX = arrayOf("", "Greater ", "Ancient ", "Mythic ")
    private val RARITY_GLYPH = arrayOf("◇", "◆", "✦", "★")
    private val RARITY_COLOR = arrayOf(
        Color.rgb(154, 160, 166), Color.rgb(79, 195, 247),
        Color.rgb(206, 147, 216), Color.rgb(255, 213, 79))
    private val STAR_GLYPHS = arrayOf("·", "⋆", "✦", "✧")

    private val SLOT_ICONS = arrayOf("🪖", "🦺", "👟", "💍", "📿")
    private val SLOT_NAMES = arrayOf("Helm", "Vest", "Boots", "Ring", "Amulet")
    private val AFFIX_TPLS = listOf(
        AffixTpl("armor", "🛡", 7f, false, "armor"),
        AffixTpl("hp", "❤️", 13f, true, "max HP"),
        AffixTpl("dodge", "👟", 3.5f, true, "dodge"),
        AffixTpl("thorns", "🌵", 7f, false, "thorns dmg"),
        AffixTpl("regen", "💚", 0.7f, false, "HP/s"),
        AffixTpl("might", "⚔️", 7f, true, "damage"),
        AffixTpl("haste", "⚡", 6f, true, "atk speed"),
        AffixTpl("luck", "🍀", 10f, true, "XP gain"),
        AffixTpl("magnet", "🧲", 22f, true, "pickup range"))

    private val META_PERKS = listOf(
        MetaPerk("hp", "❤️", "Vitality", "+10% max HP per level"),
        MetaPerk("dmg", "⚔️", "Might", "+6% damage per level"),
        MetaPerk("mag", "🧲", "Reach", "+15% pickup range per level"),
        MetaPerk("arm", "🛡️", "Bulwark", "+6 armor per level (active from spawn)"),
        MetaPerk("insight", "🎲", "Insight", "+1 level-up reroll per level", 3),
        MetaPerk("ars", "🧰", "Arsenal", "+1 weapon slot per level", 2),
        MetaPerk("ban", "🚫", "Banish", "+1 banish per run (remove bad offers)", 2))

    private val ELITE_AFFIX = arrayOf("🛡 SHIELDED", "💨 SWIFT", "🎯 SNIPER", "💣 BOMBARDIER")
    private val ELITE_COLOR = arrayOf(Color.rgb(110, 180, 255), Color.WHITE, RED, ORANGE)
    private val ELITE_ICON = arrayOf("🛡", "💨", "🎯", "💣")
    private val BOSS_NAMES = arrayOf("👹 OGRE", "🕷️ WIDOW", "🐲 DRAKE", "🌩️ TEMPEST")
    private val BOSS_GLYPH = arrayOf("👹", "🕷️", "🐲", "🌩️")

    fun weaponSlots() = 4 + (metaLv["ars"] ?: 0)

    private val templates = listOf(
        Tpl("power", "✦", "Power Glyph", 99,
            { _, m -> "+${(22 * m).roundToInt()}% damage" },
            { p, m -> p.damage *= 1f + 0.22f * m }),
        Tpl("rapid", "⚡", "Rapid Cast", 99,
            { _, m -> "+${(16 * m).roundToInt()}% fire rate" },
            { p, m -> p.fireRate *= 1f + 0.16f * m }),
        Tpl("multi", "∴", "Multishot", 7,
            { _, m -> "+${if (m >= 2.7f) 2 else 1} projectile(s)" },
            { p, m -> p.bulletCount += if (m >= 2.7f) 2 else 1 },
            avail = { p -> p.bulletCount < 7 }),
        Tpl("pierce", "⇒", "Piercing Glyphs", 6,
            { _, _ -> "bullets pass through +1 enemy" },
            { p, _ -> p.pierce += 1 },
            avail = { p -> p.pierce < 6 }),
        Tpl("seeker", "🌀", "Seeker Glyphs", 3,
            { _, _ -> "bullets curve toward enemies" },
            { p, m -> p.homing = min(9f, p.homing + 2.4f * m) }),
        Tpl("swift", "👟", "Swift Steps", 99,
            { _, m -> "+${(12 * m).roundToInt()}% move speed" },
            { p, m -> p.speed *= 1f + 0.12f * m }),
        Tpl("vitality", "❤️", "Vitality", 99,
            { _, m -> "+${(25 * m).roundToInt()} max HP, heal same" },
            { p, m -> val h = 25f * m; p.baseMaxHp += h; recalcEquipment(); p.hp = min(p.maxHp, p.hp + h) }),
        Tpl("magnet", "🧲", "Gem Magnet", 99,
            { _, m -> "+${(50 * m).roundToInt()}% pickup range" },
            { p, m -> p.magnet *= 1f + 0.5f * m }),
        Tpl("precision", "🎯", "Precision", 99,
            { _, m -> "+${(6f * m).roundToInt()}% crit, +${(0.3f * m * 10f).roundToInt() / 10f}× crit dmg" },
            { p, m -> p.critChance = min(0.85f, p.critChance + 0.06f * m); p.critMult += 0.3f * m }),
        Tpl("vampire", "🧛", "Vampiric Glyphs", 6,
            { _, m -> "heal ${(1.5f * m).roundToInt().coerceAtLeast(1)} HP per kill" },
            { p, m -> p.lifesteal += (1.5f * m).roundToInt().coerceAtLeast(1).toFloat() }),
        Tpl("mend", "💚", "Mending", 99,
            { _, m -> "regen ${(0.8f * m * 10f).roundToInt() / 10f} HP/s" },
            { p, m -> p.regen += 0.8f * m }),
        Tpl("aegis", "🛡️", "Aegis", 3,
            { _, _ -> "+1 shield: blocks a hit, recharges" },
            { p, _ -> p.shieldMax += 1; p.shield += 1 },
            avail = { p -> p.shieldMax < 3 }),
        Tpl("backfire", "↩", "Backfire", 1,
            { _, _ -> "also fire backwards" },
            { p, _ -> p.rearGun = true },
            avail = { p -> !p.rearGun }),
        Tpl("wings", "⇔", "Side Cannons", 2,
            { _, _ -> "also fire sideways" },
            { p, _ -> p.sideGuns += 1 },
            avail = { p -> p.sideGuns < 2 }),
        Tpl("heavy", "●", "Heavy Glyphs", 99,
            { _, m -> "+${(30 * m).roundToInt()}% bullet size, +${(8 * m).roundToInt()}% dmg" },
            { p, m -> p.bulletSize *= 1f + 0.3f * m; p.damage *= 1f + 0.08f * m }),
        Tpl("velocity", "»", "Velocity", 99,
            { _, m -> "+${(25 * m).roundToInt()}% bullet speed & range" },
            { p, m -> p.bulletSpeed *= 1f + 0.25f * m }),
        Tpl("greed", "🍀", "Lucky Clover", 99,
            { _, m -> "+${(25 * m).roundToInt()}% XP from gems" },
            { p, m -> p.luck *= 1f + 0.25f * m }),
        Tpl("nova", "✸", "Pulse Nova", 3,
            { _, _ -> "periodic blast: damages & erases nearby bullets" },
            { p, _ -> p.nova += 1; p.novaTimer = min(p.novaTimer, 2f) }),
    )

    private val weaponTpls = listOf(
        WeaponTpl("w_stream", "✦", "Glyph Stream", 5, "precision", "🎯", 3,
            "🌌", "Galaxy Lance", "pierces everything, +80% dmg, golden rail shots",
            "STARFIRE", "every 6th shot is a bursting star ✹ (AoE)",
            { l -> "+12% dmg, +8% rate (Lv$l)" }),
        WeaponTpl("w_halo", "✨", "Halo Spirits", 6, "swift", "👟", 3,
            "⚔️", "Blade Halo", "2 extra blades, double contact dmg, blades fire shards",
            "WHIRLWIND", "every 4s blades sweep a damaging wave",
            { l -> "$l orbiting spirits, +contact dmg" }),
        WeaponTpl("w_hornet", "🐝", "Wasp Volley", 5, "rapid", "⚡", 3,
            "👑", "Swarm Queen", "wasps split into 3 mini wasps on impact",
            "ROYAL JELLY", "wasp kills spill bonus gems 🍯",
            { l -> "${1 + l} homing wasps per volley" }),
        WeaponTpl("w_meteor", "☄️", "Meteor Call", 5, "power", "✦", 3,
            "🌑", "Armageddon", "+2 meteors, blast radius ×1.6",
            "AFTERMATH", "blasts leave burning ground 🔥",
            { l -> "$l meteor(s), +blast dmg" }),
        WeaponTpl("w_frost", "❄️", "Frost Sigil", 5, "aegis", "🛡️", 2,
            "🧊", "Absolute Zero", "pulses freeze enemies solid & hit harder",
            "SHATTER", "frozen foes explode into ice shards on death",
            { l -> "bigger chill pulse, stronger slow" }),
        WeaponTpl("w_thunder", "⚡", "Thunder Sigil", 5, "greed", "🍀", 3,
            "🌩️", "Storm Crown", "lightning chains between foes",
            "OVERLOAD", "strikes stun & vaporize nearby bullets",
            { l -> "+bolt dmg, extra strikes at Lv3/5" }),
        WeaponTpl("w_boom", "🪃", "Boomerang", 5, "magnet", "🧲", 3,
            "🌀", "Tempest", "+2 boomerangs, bigger, catches yank enemies in",
            "TAILWIND", "catches grant +30% move speed & pull foes",
            { l -> "${1 + (l - 1) / 2} boomerang(s), +30% dmg per level" }),
        WeaponTpl("w_mine", "🧨", "Mine Layer", 5, "vampire", "🧛", 3,
            "💀", "Pestilence", "3× mines, every blast leaves poison ☠️",
            "PANDEMIC", "enemies dying in poison spread new clouds",
            { l -> "mine every ${(3.6f - 0.3f * l).coerceAtLeast(1.6f)}s, +dmg & radius" }),
    )

    private val achvTpls = listOf(
        Achv("a_evo1", "★", "First Evolution", "evolve any weapon"),
        Achv("a_evo3", "★★", "Trinity", "3 evolutions in one run"),
        Achv("a_boss1", "☄", "Boss Slayer", "defeat a boss"),
        Achv("a_boss3", "☄☄", "Boss Buster", "3 bosses in one run"),
        Achv("a_asc", "⚡", "Ascended", "survive 10:00 to ascend"),
        Achv("a_hard", "💀", "Hellwalker", "ascend on HARD"),
        Achv("a_night", "🕳️", "Into the Void", "survive to NIGHTMARE"),
        Achv("a_mine", "🧨", "Pestilence", "evolve the Mine Layer"),
        Achv("a_combo", "🔥", "Chain Master", "reach combo ×50"),
        Achv("a_graze", "✧", "Untouchable", "graze 200 bullets in a run"),
        Achv("a_kills", "☠☠", "Exterminator", "1000 lifetime kills"),
        Achv("a_plate", "🛡", "Full Plate", "all 5 slots ★ armor"))

    init { Thread { initAudio() }.start() } // synth off the UI thread; sfx() no-ops until loaded

    var state = State.MENU
    var W = 1f; var H = 1f
    var t = 0f; var animT = 0f; var menuT = 0f; var overTimer = 0f
    var kills = 0
    var shake = 0f; var last = 0L
    var hitStop = 0f

    var grazes = 0; var dmgTaken = 0f; var chestsOpened = 0
    var comboKills = 0; var comboTimer = 0f; var bestCombo = 0
    var shardsRun = 0; var scoreRun = 0
    var rerolls = 0; var bossNum = 0; var bossesDown = 0
    var ascended = false; var ascT = 0f
    var nightmare = false

    // ── ads: revive & double ──
    var reviveUsed = false; var reviveOffer = false
    var revivePending = false
    var doubleUsed = false; var doubleOffer = false
    var doublePending = false
    val reviveRect = RectF(); val giveRect = RectF(); val dblRect = RectF()

    var player = Player()
    val bullets = ArrayList<Bullet>()
    val ebullets = ArrayList<EBullet>()
    val enemies = ArrayList<Enemy>()
    val pendingSpawns = ArrayList<Enemy>()
    val gems = ArrayList<Gem>()
    val hearts = ArrayList<Heart>()
    val chests = ArrayList<Chest>()
    val powers = ArrayList<Power>()
    val mines = ArrayList<Mine>()
    val meteors = ArrayList<Meteor>()
    val fireZones = ArrayList<FireZone>()
    val pendingZones = ArrayList<FireZone>()
    val bolts = ArrayList<Bolt>()
    val parts = ArrayList<Particle>()
    val texts = ArrayList<FloatText>()
    val rings = ArrayList<Ring>()
    val stars = ArrayList<Star>()
    val upgrades = ArrayList<Upgrade>()
    val stacks = HashMap<String, Int>()
    val evolved = HashSet<String>()
    val notifiedEvo = HashSet<String>()
    val banished = HashSet<String>()
    val equipment = arrayOfNulls<ArmorPiece>(5)
    val cardRects = ArrayList<RectF>()
    val banRects = ArrayList<RectF>()

    var starterOffers: List<WeaponTpl> = emptyList()

    var pendingArmor: ArmorPiece? = null
    var pendingOld: ArmorPiece? = null
    val newRect = RectF(); val oldRect = RectF()
    val equipRect = RectF(); val salvageRect = RectF()

    var starCount = 0
    var whirlTimer = 4f

    var codexFrom = State.MENU
    var metaOpen = false
    var achvOpen = false

    val pauseRect = RectF(12f, 16f, 58f, 58f)
    val metaRect = RectF(12f, 8f, 156f, 48f)
    val diffRect = RectF(12f, 56f, 156f, 96f)
    val achvRect = RectF(12f, 104f, 156f, 144f)
    val dailyRect = RectF(12f, 152f, 156f, 192f)
    val metaCloseRect = RectF(); val achvCloseRect = RectF()
    val resumeRect = RectF(); val restartRect = RectF()
    val rerollRect = RectF()
    val sfxRect = RectF(); val musRect = RectF(); val fxRect = RectF()
    val buyRects = ArrayList<RectF>()

    var boss: Enemy? = null
    var spawnTimer = 1f; var eliteTimer = 25f; var bossTimer = 120f; var burstTimer = 34f
    var chestTimer = 55f
    var joyDown = false
    var joyBaseX = 0f; var joyBaseY = 0f; var joyX = 0f; var joyY = 0f

    private var timeSec = -1; private var timeStr = "00:00"
    private var hpKey = -1; private var hpStr = ""

    val achvQueue = ArrayDeque<String>()
    var achvTimer = 0f

    var banishes = 0

    fun dailySeed(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 +
            c.get(Calendar.DAY_OF_MONTH)
    }
    fun dailyKey() = "daily_${dailySeed()}"
    fun dailyBest() = prefs.getInt(dailyKey(), 0)

    val d get() = if (dailyMode) 1 else diff
    val diffHp get() = floatArrayOf(0.75f, 1f, 1.5f)[d]
    val diffDmg get() = floatArrayOf(0.8f, 1f, 1.25f)[d]
    val diffXp get() = floatArrayOf(0.85f, 1f, 1.15f)[d]
    val diffMult get() = floatArrayOf(0.6f, 1f, 1.5f)[d]
    val diffName get() = arrayOf("🌙 EASY", "⚔️ NORMAL", "💀 HARD")[d]

    fun ascHp() = when {
        nightmare -> 1.8f + ascT / 50f
        ascended -> 1.5f + ascT / 60f
        else -> 1f
    }
    fun ascDmg() = when {
        nightmare -> 1.35f + ascT / 80f
        ascended -> 1.2f + ascT / 90f
        else -> 1f
    }

    fun evo(id: String) = id in evolved

    // ═══════════ AUDIO: synth → wav → SoundPool ═══════════

    private fun initAudio() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            sp = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
            val idName = HashMap<Int, String>()
            val dir = context.cacheDir
            for ((name, pcm) in synthAll()) {
                val f = File(dir, "gs_$name.wav")
                writeWav(f, pcm)
                val sid = sp!!.load(f.absolutePath, 1)
                idName[sid] = name; sfxIds[name] = sid
            }
            sp?.setOnLoadCompleteListener { _, sid, st ->
                if (idName[sid] == "music" && st == 0) musicReady = true
            }
        } catch (_: Exception) { sp = null }
    }

    private fun gen(dur: Float, f0: Float, f1: Float, type: Int, vol: Float, exp: Float = 4f): ShortArray {
        val sr = 22050
        val n = (sr * dur).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        var ph = 0f
        for (i in 0 until n) {
            val f = i / n.toFloat()
            val fr = f0 + (f1 - f0) * f
            ph += fr / sr
            val cyc = ((ph % 1f) + 1f) % 1f
            val raw = when (type) {
                0 -> sin(cyc * TAU)
                1 -> if (cyc < 0.5f) 1f else -1f
                2 -> cyc * 2f - 1f
                else -> Random.nextFloat() * 2f - 1f
            }
            val env = Math.pow((1f - f).toDouble(), exp.toDouble()).toFloat()
            out[i] = (raw * env * vol * 32000f).toInt().toShort()
        }
        return out
    }

    private fun cat(vararg a: ShortArray): ShortArray {
        var n = 0; for (s in a) n += s.size
        val o = ShortArray(n); var i = 0
        for (s in a) { System.arraycopy(s, 0, o, i, s.size); i += s.size }
        return o
    }

    private fun synthMusic(): ShortArray {
        val sr = 22050
        val bpm = 112f
        val beat = 60f / bpm
        val total = (8 * 4 * beat * sr).toInt()
        val out = ShortArray(total)
        val bassN = floatArrayOf(110f, 87.31f, 65.41f, 98f)
        val arpN = arrayOf(
            floatArrayOf(440f, 523.25f, 659.25f),
            floatArrayOf(349.23f, 440f, 523.25f),
            floatArrayOf(261.63f, 329.63f, 392f),
            floatArrayOf(392f, 493.88f, 587.33f))
        for (i in 0 until total) {
            val ti = i / sr.toFloat()
            val bp = ti / beat
            val bar = (bp / 4f).toInt() % 4
            val bi = bp.toInt() % 4
            val eighth = (bp * 2f).toInt() % 8
            val bEnv = Math.pow(1.0 - ((bp % 1f)).toDouble(), 2.2).toFloat()
            val bCyc = (ti * bassN[bar]) % 1f
            val bassV = (if (bCyc < 0.5f) 1f else -1f) * bEnv * 0.15f
            val aEnv = Math.pow(1.0 - ((bp * 2f) % 1f).toDouble(), 1.8).toFloat()
            val aCyc = (ti * arpN[bar][eighth % 3]) % 1f
            val tri = if (aCyc < 0.5f) aCyc * 4f - 1f else 3f - aCyc * 4f
            val arpV = tri * aEnv * 0.10f
            var kickV = 0f
            if (bi == 0 || bi == 2) {
                val inB = bp % 1f
                val kf = 40f + 110f * Math.pow((1f - inB).toDouble(), 2.0).toFloat()
                kickV = sin(((ti * kf) % 1f) * TAU) *
                    Math.pow((1f - inB).toDouble(), 3.0).toFloat() * 0.30f
            }
            var hatV = 0f
            if (eighth % 2 == 1) {
                val hp = (bp * 2f) % 1f
                hatV = (Random.nextFloat() * 2f - 1f) *
                    Math.pow((1f - hp).toDouble(), 4.0).toFloat() * 0.05f
            }
            val v = (bassV + arpV + kickV + hatV).coerceIn(-1f, 1f)
            out[i] = (v * 32000f).toInt().toShort()
        }
        return out
    }

    private fun synthAll(): Map<String, ShortArray> {
        val m = HashMap<String, ShortArray>()
        m["shoot"] = gen(0.05f, 900f, 500f, 1, 0.13f)
        m["hit"] = gen(0.04f, 0f, 0f, 3, 0.09f)
        m["crit"] = gen(0.07f, 1500f, 2100f, 0, 0.20f)
        m["kill"] = gen(0.10f, 320f, 70f, 2, 0.18f)
        m["gem"] = gen(0.05f, 1320f, 1320f, 0, 0.12f)
        m["pickup"] = cat(gen(0.06f, 880f, 880f, 0, 0.2f), gen(0.08f, 1320f, 1320f, 0, 0.2f))
        m["hurt"] = gen(0.20f, 220f, 55f, 1, 0.34f)
        m["die"] = gen(0.55f, 300f, 40f, 1, 0.4f, 2f)
        m["level"] = cat(gen(0.08f, 523f, 523f, 0, 0.22f), gen(0.08f, 659f, 659f, 0, 0.22f),
            gen(0.08f, 784f, 784f, 0, 0.22f), gen(0.14f, 1046f, 1046f, 0, 0.25f))
        m["chest"] = cat(gen(0.07f, 660f, 660f, 1, 0.2f), gen(0.10f, 990f, 990f, 1, 0.2f))
        m["evolve"] = cat(gen(0.07f, 392f, 392f, 1, 0.2f), gen(0.07f, 523f, 523f, 1, 0.2f),
            gen(0.07f, 659f, 659f, 1, 0.2f), gen(0.07f, 784f, 784f, 1, 0.2f),
            gen(0.20f, 1046f, 1046f, 0, 0.25f))
        m["boss"] = cat(gen(0.45f, 95f, 45f, 2, 0.4f, 1.6f), gen(0.3f, 0f, 0f, 3, 0.18f, 2.5f))
        m["nova"] = gen(0.28f, 700f, 80f, 2, 0.28f)
        m["thunder"] = cat(gen(0.16f, 0f, 0f, 3, 0.3f, 3f), gen(0.12f, 180f, 60f, 2, 0.22f))
        m["whoosh"] = gen(0.16f, 240f, 900f, 2, 0.16f, 1.5f)
        m["ascend"] = cat(gen(0.5f, 80f, 400f, 2, 0.35f, 1.2f),
            gen(0.4f, 523f, 1046f, 0, 0.28f, 1f))
        m["music"] = synthMusic()
        return m
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val sr = 22050
        val dataLen = pcm.size * 2
        val buf = ByteArray(44 + dataLen)
        fun le(off: Int, v: Int) {
            buf[off] = v.toByte(); buf[off + 1] = (v shr 8).toByte()
            buf[off + 2] = (v shr 16).toByte(); buf[off + 3] = (v shr 24).toByte()
        }
        buf[0] = 'R'.code.toByte(); buf[1] = 'I'.code.toByte(); buf[2] = 'F'.code.toByte(); buf[3] = 'F'.code.toByte()
        le(4, 36 + dataLen)
        buf[8] = 'W'.code.toByte(); buf[9] = 'A'.code.toByte(); buf[10] = 'V'.code.toByte(); buf[11] = 'E'.code.toByte()
        buf[12] = 'f'.code.toByte(); buf[13] = 'm'.code.toByte(); buf[14] = 't'.code.toByte(); buf[15] = ' '.code.toByte()
        le(16, 16); le(20, 1); le(22, 1); le(24, sr); le(28, sr * 2); le(32, 2); le(34, 16)
        buf[36] = 'd'.code.toByte(); buf[37] = 'a'.code.toByte(); buf[38] = 't'.code.toByte(); buf[39] = 'a'.code.toByte()
        le(40, dataLen)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            buf[44 + i * 2] = v.toByte()
            buf[45 + i * 2] = (v shr 8).toByte()
        }
        f.writeBytes(buf)
    }

    fun sfx(name: String, every: Int = 1, vol: Float = 1f) {
        if (!soundOn) return
        val pool = sp ?: return
        val c = (sfxCnt[name] ?: 0) + 1
        sfxCnt[name] = c
        if (every > 1 && c % every != 0) return
        val id = sfxIds[name] ?: return
        pool.play(id, vol, vol, 1, 0, 1f)
    }

    fun syncMusic() {
        val want = musicOn && musicReady && sp != null && state != State.PAUSE && !focusLost
        if (want && (musicStream == 0 || curMusicRate != musicRate)) {
            if (!focusRequested) {
                focusRequested = true
                focusLost = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            if (!focusLost) {
                if (musicStream != 0) sp?.stop(musicStream)
                musicStream = sp!!.play(musicId, 0.32f, 0.32f, 0, -1, musicRate)
                curMusicRate = musicRate
            }
        } else if (!want && musicStream != 0) {
            sp?.stop(musicStream); musicStream = 0
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            if (focusRequested) (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .abandonAudioFocus(focusListener)
            sp?.release()
        } catch (_: Exception) { }
        sp = null
    }
    // ═══════════ persistence / settings ═══════════

    fun savePrefs() {
        prefs.edit().putFloat("best", best).putInt("bestScore", bestScore)
            .putInt("shards", shards).putInt("tkills", totalKills)
            .putStringSet("evo", discovered).putStringSet("achv", achvUnlocked).apply()
    }
    fun saveSettings() = prefs.edit().putInt("diff", diff).putBoolean("sound", soundOn)
        .putBoolean("music", musicOn).putBoolean("fx", fxOn).putBoolean("perf", perfMode).apply()
    fun saveMeta() {
        val ed = prefs.edit()
        for ((k, v) in metaLv) ed.putInt("meta_$k", v)
        ed.apply()
    }
    fun metaCost(lvl: Int) = (lvl + 1) * 3
    fun buyMeta(i: Int) {
        val pk = META_PERKS[i]
        val lv = metaLv[pk.id] ?: 0
        if (lv >= pk.maxLvl) return
        val cost = metaCost(lv)
        if (shards >= cost) {
            shards -= cost; metaLv[pk.id] = lv + 1
            saveMeta(); savePrefs(); sfx("pickup"); buzz()
        }
    }

    // ═══════════ activity bridges ═══════════

    fun autoPause() {
        if (state == State.PLAY) {
            saveRunSnapshot()
            state = State.PAUSE; joyDown = false
        }
    }
    fun onBack(): Boolean = when (state) {
        State.PLAY -> { saveRunSnapshot(); state = State.PAUSE; joyDown = false; true }
        State.PAUSE -> { state = State.PLAY; player.invuln = max(player.invuln, 1f); true }
        State.CODEX -> { state = codexFrom; true }
        State.STARTER -> { state = State.MENU; true }
        else -> false
    }

    fun buzz() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun buzzStrong() = performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

    // ═══════════ achievements ═══════════

    fun achvCond(id: String): Boolean = when (id) {
        "a_evo1" -> evolved.size >= 1
        "a_evo3" -> evolved.size >= 3
        "a_boss1" -> bossesDown >= 1
        "a_boss3" -> bossesDown >= 3
        "a_asc" -> ascended
        "a_hard" -> ascended && diff == 2 && !dailyMode
        "a_night" -> nightmare
        "a_mine" -> evo("w_mine")
        "a_combo" -> bestCombo >= 50
        "a_graze" -> grazes >= 200
        "a_kills" -> totalKills >= 1000
        "a_plate" -> equipment.all { it != null && it.rarity == 3 }
        else -> false
    }

    fun checkAchv() {
        for (a in achvTpls) {
            if (a.id in achvUnlocked) continue
            if (achvCond(a.id)) {
                achvUnlocked.add(a.id)
                savePrefs()
                val first = achvQueue.isEmpty()
                achvQueue.addLast(a.id)
                if (first) achvTimer = 2.6f
                buzz()
                sfx("evolve", 1, 0.8f)
            }
        }
    }

    // ═══════════ lifecycle / loop ═══════════

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        W = w / ds; H = h / ds
        stars.clear()
        repeat(30) { stars.add(Star(Random.nextFloat() * W, Random.nextFloat() * H,
            STAR_GLYPHS.random(), Random.nextFloat() * 6f)) }
        player.x = W / 2f; player.y = H / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        if (last == 0L) last = now
        val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        last = now
        animT += dt
        syncMusic()
        if (achvQueue.isNotEmpty()) {
            achvTimer -= dt
            if (achvTimer <= 0f) { achvQueue.removeFirst(); achvTimer = 2.6f }
        }
        try {
            when (state) {
                State.PLAY -> update(dt)
                State.OVER -> { overTimer += dt; updateFx(dt) }
                State.MENU -> menuT += dt
                else -> {}
            }
            canvas.save()
            canvas.scale(ds, ds)
            render(canvas)
            canvas.restore()
        } catch (e: Exception) {
            Log.e("GlyphStorm", "loop error", e)
            runCatching { canvas.restore() }
            hardReset()
        }
        postInvalidateOnAnimation()
    }

    fun hardReset() {
        try {
            bullets.clear(); ebullets.clear(); enemies.clear(); pendingSpawns.clear()
            gems.clear(); hearts.clear(); chests.clear(); powers.clear(); mines.clear()
            meteors.clear(); fireZones.clear(); pendingZones.clear(); bolts.clear()
            parts.clear(); texts.clear(); rings.clear()
            boss = null; state = State.MENU
        } catch (_: Exception) { }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x / ds; val y = ev.y / ds
        when (state) {
            State.MENU -> if (ev.action == MotionEvent.ACTION_DOWN) {
                if (achvOpen) {
                    if (achvCloseRect.contains(x, y)) achvOpen = false
                } else if (metaOpen) {
                    if (metaCloseRect.contains(x, y)) metaOpen = false
                    else for (i in buyRects.indices) if (buyRects[i].contains(x, y)) { buyMeta(i); break }
                } else when {
                    contRect.contains(x, y) -> { restoreRun(); buzz() }
                    sfxRect.contains(x, y) -> { soundOn = !soundOn; saveSettings(); buzz() }
                    musRect.contains(x, y) -> { musicOn = !musicOn; saveSettings(); syncMusic(); buzz() }
                    fxRect.contains(x, y) -> { fxOn = !fxOn; saveSettings(); buzz() }
                    perfRect.contains(x, y) -> { perfMode = !perfMode; saveSettings(); buzz() }
                    metaRect.contains(x, y) -> { metaOpen = true; buzz() }
                    diffRect.contains(x, y) -> { diff = (diff + 1) % 3; saveSettings(); buzz() }
                    achvRect.contains(x, y) -> { achvOpen = true; buzz() }
                    dailyRect.contains(x, y) -> { startGame(daily = true); buzz() }
                    codexRectF().contains(x, y) -> { codexFrom = State.MENU; state = State.CODEX }
                    else -> startGame()
                }
            }
            State.STARTER -> if (ev.action == MotionEvent.ACTION_DOWN) {
                for (i in cardRects.indices) if (cardRects[i].contains(x, y)) { pickStarter(i); break }
            }
            State.OVER -> if (ev.action == MotionEvent.ACTION_DOWN) {
                when {
                    reviveOffer && reviveRect.contains(x, y) -> chooseRevive()
                    reviveOffer && giveRect.contains(x, y) -> giveUp()
                    doubleOffer && dblRect.contains(x, y) -> chooseDouble()
                    codexRectF().contains(x, y) -> { codexFrom = State.OVER; state = State.CODEX }
                    else -> if (!reviveOffer && overTimer > 0.7f) startGame(dailyMode)
                }
            }
            State.CODEX -> if (ev.action == MotionEvent.ACTION_DOWN) state = codexFrom
            State.LEVELUP -> if (ev.action == MotionEvent.ACTION_DOWN) {
                if (rerolls > 0 && rerollRect.contains(x, y)) {
                    rerolls--; rollUpgrades(); buzz()
                } else {
                    for (i in banRects.indices) {
                        val br = banRects[i]
                        if (br.width() > 0f && br.contains(x, y)) {
                            val u = upgrades.getOrNull(i)
                            if (u != null && u.kind in 0..1 && u.id != "feast" && banishes > 0) {
                                banishes--
                                banished.add(u.id)
                                rollUpgrades()
                                buzz(); sfx("hit", 1, 0.8f)
                            }
                            return true
                        }
                    }
                    for (i in cardRects.indices) if (cardRects[i].contains(x, y)) { choose(i); break }
                }
            }
            State.ARMOR -> if (ev.action == MotionEvent.ACTION_DOWN) {
                when {
                    equipRect.contains(x, y) || newRect.contains(x, y) -> chooseArmor(true)
                    salvageRect.contains(x, y) || oldRect.contains(x, y) -> chooseArmor(false)
                }
            }
            State.PAUSE -> if (ev.action == MotionEvent.ACTION_DOWN) when {
                resumeRect.contains(x, y) -> { state = State.PLAY; player.invuln = max(player.invuln, 1f) }
                restartRect.contains(x, y) -> { clearSnapshot(); startGame(dailyMode) }
                codexRectF().contains(x, y) -> { codexFrom = State.PAUSE; state = State.CODEX }
                sfxRect.contains(x, y) -> { soundOn = !soundOn; saveSettings(); buzz() }
                musRect.contains(x, y) -> { musicOn = !musicOn; saveSettings(); syncMusic(); buzz() }
                fxRect.contains(x, y) -> { fxOn = !fxOn; saveSettings(); buzz() }
                perfRect.contains(x, y) -> { perfMode = !perfMode; saveSettings(); buzz() }
                else -> {}
            }
            State.PLAY -> when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val i = ev.actionIndex
                    val px = ev.getX(i) / ds; val py = ev.getY(i) / ds
                    if (pauseRect.contains(px, py)) { saveRunSnapshot(); state = State.PAUSE; buzz() }
                    else {
                        joyPointer = ev.getPointerId(i)
                        joyDown = true; joyBaseX = px; joyBaseY = py; joyX = px; joyY = py
                    }
                }
                MotionEvent.ACTION_POINTER_UP ->
                    if (ev.getPointerId(ev.actionIndex) == joyPointer) joyDown = false
                MotionEvent.ACTION_MOVE -> {
                    val i = ev.findPointerIndex(joyPointer)
                    if (i >= 0) { joyX = ev.getX(i) / ds; joyY = ev.getY(i) / ds }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { joyDown = false; joyPointer = -1 }
            }
        }
        return true
    }

    fun codexRectF() = RectF(W - safeRight - 140f, safeTop + 8f, W - safeRight - 12f, safeTop + 48f)

    // ═══════════ derived stats ═══════════

    fun pDmg() = player.damage * (1f + player.mightEq)
    fun pMagnet(): Float =
        if (player.vacuumT > 0f) 4000f else player.magnet * (1f + player.magnetEq)
    fun streamLvl() = stacks["w_stream"] ?: 1
    fun streamDmgMult() = 1f + 0.12f * (streamLvl() - 1)
    fun streamRateMult() = 1f + 0.08f * (streamLvl() - 1)

    fun recalcEquipment() {
        val p = player
        p.armorPts = 0f; p.dodgePct = 0f; p.thorns = 0f; p.regenEq = 0f
        var hpPct = 0f
        p.mightEq = 0f; p.hasteEq = 0f; p.luckEq = 0f; p.magnetEq = 0f
        for (pc in equipment) if (pc != null) for ((id, v) in pc.affixes) when (id) {
            "armor" -> p.armorPts += v
            "hp" -> hpPct += v
            "dodge" -> p.dodgePct += v
            "thorns" -> p.thorns += v
            "regen" -> p.regenEq += v
            "might" -> p.mightEq += v
            "haste" -> p.hasteEq += v
            "luck" -> p.luckEq += v
            "magnet" -> p.magnetEq += v
        }
        p.armorPts += 6f * (metaLv["arm"] ?: 0)
        p.dodgePct = p.dodgePct.coerceAtMost(0.45f)
        val oldMax = p.maxHp
        p.maxHp = p.baseMaxHp * (1f + hpPct)
        if (oldMax > 0f) p.hp *= p.maxHp / oldMax
    }

    // ═══════════ update ═══════════

    fun update(dtIn: Float) {
        var dt = dtIn
        if (hitStop > 0f) { hitStop -= dtIn; dt = dtIn * 0.12f }
        t += dt
        if (ascended) ascT += dt
        val p = player
        p.invuln -= dt
        p.tailwindT = max(0f, p.tailwindT - dt)

        if (!ascended && t >= 600f) {
            ascended = true; ascT = 0f
            musicRate = 1.12f
            rings.add(Ring(p.x, p.y, 520f, STORM))
            rings.add(Ring(p.x, p.y, 380f, GOLD))
            ftext(p.x, p.y - 110f, "⚡ ASCENSION ⚡", STORM, 44f)
            ftext(p.x, p.y - 70f, "the storm turns on you — rewards ×1.5", STORM, 18f)
            shake = 20f; hitStop = max(hitStop, 0.18f)
            buzzStrong()
            sfx("ascend"); sfx("boss")
        }
        if (ascended && !nightmare && t >= 1200f) {
            nightmare = true
            musicRate = 1.25f
            rings.add(Ring(p.x, p.y, 560f, VOID))
            rings.add(Ring(p.x, p.y, 400f, RED))
            ftext(p.x, p.y - 110f, "☠☠ NIGHTMARE ☠☠", VOID, 42f)
            ftext(p.x, p.y - 70f, "the void hungers — rewards ×1.25", VOID, 17f)
            shake = 22f; hitStop = max(hitStop, 0.2f)
            buzzStrong()
            sfx("ascend"); sfx("boss")
        }

        checkAchv()

        var mx = 0f; var my = 0f
        if (joyDown) {
            val dx = joyX - joyBaseX; val dy = joyY - joyBaseY
            val len = hypot(dx, dy)
            if (len > 14f) { mx = dx / len; my = dy / len }
        }
        val spd = p.speed * (1f + if (p.tailwindT > 0f) 0.3f else 0f)
        p.x = (p.x + mx * spd * dt).coerceIn(p.r, W - p.r)
        p.y = (p.y + my * spd * dt).coerceIn(p.r, H - p.r)

        val totalRegen = p.regen + p.regenEq
        if (totalRegen > 0f) p.hp = min(p.maxHp, p.hp + totalRegen * dt)
        if (p.shield < p.shieldMax) {
            p.shieldTimer -= dt
            if (p.shieldTimer <= 0f) {
                p.shield++
                if (p.shield < p.shieldMax) p.shieldTimer = 22f
                burst(p.x, p.y, "🛡️", 3, 26f)
            }
        }

        p.fireTimer -= dt
        if (p.fireTimer <= 0f) {
            p.fireTimer += 1f / (p.fireRate * (1f + p.hasteEq) * streamRateMult())
            fire()
        }

        if (p.nova > 0) {
            p.novaTimer -= dt
            if (p.novaTimer <= 0f) { p.novaTimer = max(2.2f, 5.5f - p.nova); pulse() }
        }

        weaponUpdate(dt)
        updateMeteors(dt)
        updateMines(dt)
        updateFireZones(dt)
        if (pendingZones.isNotEmpty()) { fireZones.addAll(pendingZones); pendingZones.clear() }
        updateBullets(dt)
        updateEnemies(dt)
        updateEBullets(dt)
        updatePickups(dt)
        updateFx(dt)
        spawning(dt)

        if (pendingSpawns.isNotEmpty()) { enemies.addAll(pendingSpawns); pendingSpawns.clear() }

        comboTimer -= dt
        if (comboTimer <= 0f) comboKills = 0
        p.vacuumT = max(0f, p.vacuumT - dt)

        if (state == State.PLAY && p.xp >= p.xpNext) levelUp()
    }

    // ═══════════ WEAPON BEHAVIORS + EVOLUTION PASSIVES ═══════════

    fun weaponUpdate(dt: Float) {
        val p = player

        val hLvl = stacks["w_halo"] ?: 0
        if (hLvl > 0) {
            val blade = evo("w_halo")
            val count = hLvl + if (blade) 2 else 0
            val orbDist = if (blade) 118f else 92f
            val contact = pDmg() * (2.2f + 0.45f * hLvl) * (if (blade) 1.9f else 1f)
            for (i in 0 until count) {
                val a = animT * (if (blade) 3.4f else 2.6f) + i * (TAU / count)
                val ox = p.x + cos(a) * orbDist; val oy = p.y + sin(a) * orbDist
                for (e in enemies) {
                    if (e.dead) continue
                    if (hypot(e.x - ox, e.y - oy) < e.r + (if (blade) 20f else 16f)) {
                        e.hp -= contact * dt; e.flash = 0.08f
                        if (e.hp <= 0f) killEnemy(e)
                    }
                }
            }
            if (blade) {
                p.shardTimer -= dt
                if (p.shardTimer <= 0f) {
                    p.shardTimer = 1.1f
                    for (i in 0 until count) {
                        val a = animT * 3.4f + i * (TAU / count)
                        val ox = p.x + cos(a) * orbDist; val oy = p.y + sin(a) * orbDist
                        val e = nearestEnemy(ox, oy) ?: continue
                        val ang = atan2(e.y - oy, e.x - ox)
                        val b = Bullet(ox, oy, cos(ang) * 640f, sin(ang) * 640f,
                            pDmg() * (1.5f + 0.3f * hLvl))
                        b.r = 8f; b.glyph = "⚔"; b.pierce = 1; b.life = 1.6f
                        bullets.add(b)
                    }
                }
                whirlTimer -= dt
                if (whirlTimer <= 0f) {
                    whirlTimer = 4f
                    val R = 200f
                    for (e in enemies) {
                        if (e.dead) continue
                        if (hypot(e.x - p.x, e.y - p.y) < R + e.r) {
                            e.hp -= pDmg() * 2.4f; e.flash = 0.12f
                            if (e.hp <= 0f) killEnemy(e)
                        }
                    }
                    rings.add(Ring(p.x, p.y, R, GOLD))
                    burst(p.x, p.y, "⚔️", 8, 26f)
                    ftext(p.x, p.y - 84f, "WHIRLWIND ⚔️", GOLD, 20f)
                    shake = max(shake, 4f)
                }
            }
        }

        val hnLvl = stacks["w_hornet"] ?: 0
        if (hnLvl > 0) {
            p.hornetTimer -= dt
            if (p.hornetTimer <= 0f) {
                p.hornetTimer = 3.4f - 0.28f * hnLvl
                val split = evo("w_hornet")
                repeat(1 + hnLvl) {
                    val a = rng.nextFloat() * TAU
                    val b = Bullet(p.x, p.y, cos(a) * 300f, sin(a) * 300f,
                        pDmg() * (1.1f + 0.25f * (hnLvl - 1)))
                    b.r = 11f; b.glyph = "🐝"; b.homing = 10f; b.life = 4.5f; b.split = split
                    bullets.add(b)
                }
            }
        }

        val bLvl = stacks["w_boom"] ?: 0
        if (bLvl > 0) {
            p.boomTimer -= dt
            if (p.boomTimer <= 0f) {
                p.boomTimer = 3.2f - 0.18f * bLvl
                val tempest = evo("w_boom")
                val count = 1 + (bLvl - 1) / 2 + if (tempest) 2 else 0
                val dmg = pDmg() * (1f + 0.3f * (bLvl - 1)) * (if (tempest) 1.25f else 1f)
                val rr = (11f + 1.6f * bLvl) * (if (tempest) 1.4f else 1f)
                repeat(count) { k ->
                    val a = p.aimA + (k - (count - 1) / 2f) * 0.35f
                    val b = Bullet(p.x, p.y, cos(a) * 540f, sin(a) * 540f, dmg)
                    b.r = rr; b.glyph = "🪃"; b.boom = true; b.pierce = 99; b.life = 4f
                    bullets.add(b)
                }
                sfx("whoosh", 2, 0.8f)
            }
        }

        val mnLvl = stacks["w_mine"] ?: 0
        if (mnLvl > 0) {
            p.mineTimer -= dt
            if (p.mineTimer <= 0f) {
                p.mineTimer = max(1.6f, 3.6f - 0.3f * mnLvl)
                val pest = evo("w_mine")
                val n = if (pest) 3 else 1
                repeat(n) {
                    val a = rng.nextFloat() * TAU
                    val dd = rnd(30f, 70f)
                    mines.add(Mine(p.x + cos(a) * dd, p.y + sin(a) * dd,
                        pDmg() * (2.0f + 0.4f * mnLvl) * (if (pest) 1.15f else 1f),
                        (85f + 8f * mnLvl) * (if (pest) 1.25f else 1f)))
                }
                if (mines.size > 30) mines.removeAt(0)
            }
        }

        val mLvl = stacks["w_meteor"] ?: 0
        if (mLvl > 0) {
            p.meteorTimer -= dt
            if (p.meteorTimer <= 0f) {
                p.meteorTimer = 5.2f - 0.4f * mLvl
                val arma = evo("w_meteor")
                val n = mLvl + if (arma) 2 else 0
                repeat(n) {
                    val target = randomEnemy()
                    val tx = target?.x ?: (p.x + rnd(-300f, 300f))
                    val ty = target?.y ?: (p.y + rnd(-300f, 300f))
                    meteors.add(Meteor(tx, ty, 0.75f,
                        pDmg() * (2.2f + 0.35f * mLvl) * (if (arma) 1.3f else 1f),
                        (95f + 10f * mLvl) * (if (arma) 1.6f else 1f)))
                }
            }
        }

        val fLvl = stacks["w_frost"] ?: 0
        if (fLvl > 0) {
            p.frostTimer -= dt
            if (p.frostTimer <= 0f) {
                p.frostTimer = 5f - 0.3f * fLvl
                val zero = evo("w_frost")
                val R = 150f + 22f * fLvl
                val dmg = pDmg() * (0.9f + 0.25f * fLvl) * (if (zero) 1.8f else 1f)
                for (e in enemies) {
                    if (e.dead) continue
                    if (hypot(e.x - p.x, e.y - p.y) < R + e.r) {
                        e.hp -= dmg; e.flash = 0.1f
                        if (zero) e.freezeT = if (e.kind == 5) 0.6f else 1.6f
                        e.slowT = 3f
                        if (e.hp <= 0f) killEnemy(e)
                    }
                }
                rings.add(Ring(p.x, p.y, R, ICE))
            }
        }

        val tLvl = stacks["w_thunder"] ?: 0
        if (tLvl > 0) {
            p.thunderTimer -= dt
            if (p.thunderTimer <= 0f) {
                p.thunderTimer = 4.6f - 0.32f * tLvl
                val chains = if (evo("w_thunder")) 3 else 0
                val strikes = 1 + (tLvl - 1) / 2
                repeat(strikes) { thunderStrike(pDmg() * (1.6f + 0.35f * (tLvl - 1)), chains) }
                sfx("thunder", 2, 0.8f)
            }
        }
    }

    fun thunderStrike(dmg: Float, chains: Int) {
        val first = randomEnemy() ?: return
        var d = dmg
        hitByLightning(first, d)
        bolts.add(Bolt(first.x, first.y - 430f, first.x, first.y))
        var prev = first
        repeat(chains) {
            val nxt = nearestWithin(prev.x, prev.y, 240f, prev) ?: return@repeat
            d *= 0.65f
            hitByLightning(nxt, d)
            bolts.add(Bolt(prev.x, prev.y, nxt.x, nxt.y))
            prev = nxt
        }
    }

    fun randomEnemy(): Enemy? {
        var n = 0
        for (e in enemies) if (!e.dead) n++
        if (n == 0) return null
        var k = rng.nextInt(n)
        for (e in enemies) if (!e.dead) { if (k == 0) return e; k-- }
        return null
    }

    fun nearestWithin(x: Float, y: Float, R: Float, exclude: Enemy?): Enemy? {
        var best: Enemy? = null; var bd = Float.MAX_VALUE
        for (e in enemies) {
            if (e.dead || e === exclude) continue
            val d = hypot(e.x - x, e.y - y)
            if (d < R && d < bd) { bd = d; best = e }
        }
        return best
    }

    fun hitByLightning(e: Enemy, dmg: Float) {
        e.hp -= dmg; e.flash = 0.15f
        hitSpark(e.x, e.y, true)
        if (evo("w_thunder")) {
            e.freezeT = max(e.freezeT, 0.5f)
            if (rng.nextFloat() < 0.45f) {
                clearBulletsNear(e.x, e.y, 110f)
                rings.add(Ring(e.x, e.y, 110f, Color.rgb(150, 200, 255)))
            }
        }
        if (e.hp <= 0f) killEnemy(e)
    }

    fun updateMeteors(dt: Float) {
        val im = meteors.iterator()
        while (im.hasNext()) {
            val m = im.next()
            m.t += dt
            if (Random.nextFloat() < 0.6f)
                addParticle(m.tx + rnd(-10f, 10f), m.drawY() + 24f,
                    rnd(-30f, 30f), rnd(-150f, -60f), 0.25f, "✧", 14f, ORANGE)
            if (m.t >= m.dur) { explode(m.tx, m.ty, m.rad, m.dmg, true); im.remove() }
        }
    }

    fun updateMines(dt: Float) {
        val im = mines.iterator()
        while (im.hasNext()) {
            val m = im.next()
            if (m.arm > 0f) { m.arm -= dt; continue }
            m.fuse -= dt
            var trig = m.fuse <= 0f
            if (!trig) for (e in enemies) {
                if (e.dead) continue
                if (hypot(e.x - m.x, e.y - m.y) < 64f + e.r) { trig = true; break }
            }
            if (trig) { explodeMine(m); im.remove() }
        }
    }

    fun explodeMine(m: Mine) {
        val pest = evo("w_mine")
        for (e in enemies) {
            if (e.dead) continue
            if (hypot(e.x - m.x, e.y - m.y) < m.rad + e.r) {
                e.hp -= m.dmg; e.flash = 0.12f
                if (e.hp <= 0f) killEnemy(e)
            }
        }
        if (pest)
            pendingZones.add(FireZone(m.x, m.y, m.rad * 0.75f, m.dmg * 0.22f, 4.5f, true))
        rings.add(Ring(m.x, m.y, m.rad, if (pest) GREEN else ORANGE))
        burst(m.x, m.y, if (pest) "☠️" else "💥", 6, m.rad * 0.3f)
        shake = max(shake, 4f)
        sfx("nova", 4, 0.35f)
    }

    fun updateFireZones(dt: Float) {
        val iz = fireZones.iterator()
        while (iz.hasNext()) {
            val f = iz.next()
            f.t += dt
            val g = if (f.poison) "☠️" else "🔥"
            if (Random.nextFloat() < 0.5f)
                addParticle(f.x + rnd(-f.r * 0.7f, f.r * 0.7f), f.y + rnd(-f.r * 0.7f, f.r * 0.7f),
                    rnd(-20f, 20f), rnd(-90f, -40f), 0.4f, g, 20f, 0)
            for (e in enemies) {
                if (e.dead) continue
                if (hypot(e.x - f.x, e.y - f.y) < f.r + e.r) {
                    e.hp -= f.dps * dt
                    if (e.hp <= 0f) killEnemy(e)
                }
            }
            if (f.t >= f.dur) iz.remove()
        }
    }

    fun explode(x: Float, y: Float, rad: Float, dmg: Float, meteor: Boolean) {
        for (e in enemies) {
            if (e.dead) continue
            if (hypot(e.x - x, e.y - y) < rad + e.r) {
                e.hp -= dmg; e.flash = 0.12f
                if (e.hp <= 0f) killEnemy(e)
            }
        }
        if (meteor && evo("w_meteor"))
            fireZones.add(FireZone(x, y, rad * 0.8f, dmg * 0.3f, 3.2f))
        rings.add(Ring(x, y, rad, ORANGE))
        burst(x, y, "💥", 8, rad * 0.35f)
        shake = max(shake, 5f)
    }

    fun updateBullets(dt: Float) {
        var i = bullets.size - 1
        loop@ while (i >= 0) {
            val b = bullets[i]
            if (b.boom) {
                b.boomT += dt
                val spd = hypot(b.vx, b.vy)
                if (!b.ret) {
                    val ns = (spd * (1f - 2.0f * dt)).coerceAtLeast(0f)
                    val k = if (spd > 0.001f) ns / spd else 0f
                    b.vx *= k; b.vy *= k
                    if (ns < 120f) b.ret = true
                } else {
                    val dx = player.x - b.x; val dy = player.y - b.y
                    val dd = max(1f, hypot(dx, dy))
                    b.vx += dx / dd * 2600f * dt
                    b.vy += dy / dd * 2600f * dt
                    val s2 = hypot(b.vx, b.vy)
                    if (s2 > 950f) { b.vx *= 950f / s2; b.vy *= 950f / s2 }
                    if (dd < 34f) { boomCatch(b); bullets.removeAt(i); i--; continue }
                }
            } else if (b.homing > 0f) {
                val e = nearestEnemy(b.x, b.y)
                if (e != null) {
                    val want = atan2(e.y - b.y, e.x - b.x)
                    val cur = atan2(b.vy, b.vx)
                    var diff = want - cur
                    while (diff > PIf) diff -= TAU
                    while (diff < -PIf) diff += TAU
                    val na = cur + (b.homing * dt).coerceIn(-abs(diff), abs(diff))
                    val spd = hypot(b.vx, b.vy)
                    b.vx = cos(na) * spd; b.vy = sin(na) * spd
                }
            }
            b.x += b.vx * dt; b.y += b.vy * dt
            b.life -= dt
            if (b.life <= 0f || b.x < -60f || b.x > W + 60f || b.y < -60f || b.y > H + 60f) {
                if (b.star && b.life <= 0f) explode(b.x, b.y, 88f, b.dmg * 0.8f, false)
                if (b.boom && b.life <= 0f) boomCatch(b)
                bullets.removeAt(i); i--; continue
            }
            for (e in enemies) {
                if (e.dead || b.hit.contains(e)) continue
                val dx = e.x - b.x; val dy = e.y - b.y
                val rr = e.r + b.r
                if (dx * dx + dy * dy < rr * rr) {
                    e.hp -= b.dmg; e.flash = 0.12f
                    hitSpark(b.x, b.y, b.crit)
                    sfx("hit", 8, 0.7f)
                    if (b.crit) {
                        ftext(e.x, e.y - e.r, "✶${b.dmg.roundToInt()}", GOLD, 28f)
                        sfx("crit", 2, 0.9f)
                    }
                    if (b.split && !b.mini) {
                        b.split = false
                        repeat(3) {
                            val a2 = atan2(b.vy, b.vx) + rnd(-1.2f, 1.2f)
                            val m = Bullet(b.x, b.y, cos(a2) * 260f, sin(a2) * 260f, b.dmg * 0.4f)
                            m.r = 7f; m.glyph = "🐝"; m.homing = 9f; m.mini = true; m.life = 2f
                            bullets.add(m)
                        }
                    }
                    val wasWasp = b.glyph == "🐝"
                    b.hit.add(e)
                    var consumed = false
                    if (b.star) { explode(b.x, b.y, 88f, b.dmg * 0.8f, false); consumed = true }
                    else if (b.pierce > 0) b.pierce-- else consumed = true
                    if (consumed) { bullets.removeAt(i); i-- }
                    if (e.hp <= 0f) killEnemy(e, wasWasp)
                    continue@loop
                }
            }
            i--
        }
    }

    fun boomCatch(b: Bullet) {
        val p = player
        p.tailwindT = 1.4f
        sfx("whoosh", 3, 0.6f)
        if (evo("w_boom")) {
            val R = 130f
            for (e in enemies) {
                if (e.dead) continue
                val dx = p.x - e.x; val dy = p.y - e.y
                val dd = max(1f, hypot(dx, dy))
                if (dd < R + e.r) {
                    val pull = min(46f, dd * 0.45f)
                    e.x += dx / dd * pull; e.y += dy / dd * pull
                    e.hp -= b.dmg * 0.9f; e.flash = 0.1f; e.slowT = 1.2f
                    if (e.hp <= 0f) killEnemy(e)
                }
            }
            rings.add(Ring(b.x, b.y, 130f, CYAN))
            burst(b.x, b.y, "🌀", 5, 22f)
        }
    }

    fun updateEnemies(dt: Float) {
        enemies.removeAll { it.dead }
        val p = player
        for (e in enemies) {
            e.flash = max(0f, e.flash - dt)
            e.slowT = max(0f, e.slowT - dt)
            e.freezeT = max(0f, e.freezeT - dt)
            e.wobble += dt * 4f
            val sf = when {
                e.freezeT > 0f -> 0f
                e.slowT > 0f -> 0.45f
                else -> 1f
            }
            val dx = p.x - e.x; val dy = p.y - e.y
            val d = max(1f, hypot(dx, dy))
            val ux = dx / d; val uy = dy / d
            when (e.kind) {
                0, 2, 6 -> { e.x += ux * e.speed * sf * dt; e.y += uy * e.speed * sf * dt }
                1, 7 -> {
                    val s = sin(e.wobble * (if (e.kind == 7) 3f else 2.2f)) * e.speed *
                        (if (e.kind == 7) 0.4f else 0.9f)
                    e.x += (ux * e.speed - uy * s) * sf * dt
                    e.y += (uy * e.speed + ux * s) * sf * dt
                }
                3 -> {
                    val move = if (d > 360f) 1f else if (d < 280f) -0.7f else 0f
                    val strafe = if (sin(e.wobble) >= 0f) 0.5f else -0.5f
                    e.x += (ux * e.speed * move - uy * e.speed * strafe) * sf * dt
                    e.y += (uy * e.speed * move + ux * e.speed * strafe) * sf * dt
                    if (sf > 0f) {
                        e.fireTimer -= dt
                        if (e.fireTimer <= 0f && d < 640f) { e.fireTimer = 2.3f; aimedFan(e, 3, 0.28f) }
                    }
                }
                4 -> {
                    e.x += ux * e.speed * sf * dt; e.y += uy * e.speed * sf * dt
                    if (sf > 0f) {
                        e.fireTimer -= dt
                        if (e.fireTimer <= 0f) {
                            e.fireTimer = if (e.affix == 2 || e.affix == 3) 1.4f else 2.4f
                            e.pattern = (e.pattern + 1) % 2
                            when {
                                e.affix == 3 -> ring(e.x, e.y, 18, e.phase)
                                e.affix == 2 -> aimedFan(e, 9, 0.5f)
                                e.pattern == 0 -> ring(e.x, e.y, 16, e.phase)
                                else -> aimedFan(e, 7, 0.5f)
                            }
                            e.phase += 0.45f
                        }
                    }
                }
                8 -> {
                    when (e.pattern) {
                        0 -> {
                            e.x += ux * e.speed * sf * dt; e.y += uy * e.speed * sf * dt
                            if (d < 330f) { e.pattern = 1; e.fireTimer = 0.55f }
                        }
                        1 -> {
                            e.fireTimer -= dt
                            if (e.fireTimer <= 0f) {
                                e.pattern = 2; e.fireTimer = 0.5f
                                e.dashX = ux; e.dashY = uy
                            }
                        }
                        else -> {
                            e.x += e.dashX * 640f * sf * dt
                            e.y += e.dashY * 640f * sf * dt
                            e.fireTimer -= dt
                            if (e.fireTimer <= 0f) { e.pattern = 0; e.fireTimer = 0.9f }
                        }
                    }
                }
                9 -> {
                    val move = if (d > 340f) 1f else if (d < 260f) -0.6f else 0f
                    e.x += ux * e.speed * move * sf * dt
                    e.y += uy * e.speed * move * sf * dt
                    if (d < 430f) {
                        val pull = 62f * sf
                        p.x = (p.x - ux * pull * dt).coerceIn(p.r, W - p.r)
                        p.y = (p.y - uy * pull * dt).coerceIn(p.r, H - p.r)
                        if (rng.nextFloat() < dt * 8f)
                            addParticle(e.x + rnd(-16f, 16f), e.y + rnd(-16f, 16f),
                                0f, 0f, 0.3f, "✦", 13f, VOID)
                    }
                }
                5 -> {
                    e.x += ux * e.speed * sf * dt; e.y += uy * e.speed * sf * dt
                    if (sf > 0f) when (e.vari) {
                        1 -> {
                            if (e.phase > 0f) {
                                e.fireTimer -= dt
                                if (e.fireTimer <= 0f) {
                                    e.fireTimer = 0.085f
                                    e.spiralA += 0.24f
                                    repeat(4) { k ->
                                        val a = e.spiralA + k * (PIf / 2f)
                                        ebullet(e.x, e.y, cos(a) * 205f, sin(a) * 205f, edmg(), "✚", MAGENTA)
                                    }
                                    e.phase -= 1f
                                }
                            } else {
                                e.fireTimer -= dt
                                if (e.fireTimer <= 0f) when (rng.nextInt(3)) {
                                    0 -> { ring(e.x, e.y, 22, rng.nextFloat() * TAU); e.fireTimer = 1.9f }
                                    1 -> { aimedFan(e, 7, 0.35f); e.fireTimer = 1.8f }
                                    else -> { e.phase = 30f; e.fireTimer = 0f }
                                }
                            }
                        }
                        2 -> {
                            if (e.phase > 0f) {
                                e.fireTimer -= dt
                                if (e.fireTimer <= 0f) {
                                    e.fireTimer = 0.09f
                                    e.spiralA += 0.35f
                                    val base = atan2(p.y - e.y, p.x - e.x) + sin(e.spiralA) * 0.55f
                                    ebullet(e.x + cos(base) * 26f, e.y + sin(base) * 26f,
                                        cos(base) * 285f, sin(base) * 285f, edmg(), "♦", ORANGE)
                                    e.phase -= 1f
                                }
                            } else {
                                e.fireTimer -= dt
                                if (e.fireTimer <= 0f) when (rng.nextInt(3)) {
                                    0 -> { ring(e.x, e.y, 20, rng.nextFloat() * TAU); e.fireTimer = 1.8f }
                                    1 -> { aimedFan(e, 5, 0.3f); e.fireTimer = 1.6f }
                                    else -> { e.phase = 16f; e.spiralA = 0f; e.sweep = -e.sweep; e.fireTimer = 0f }
                                }
                            }
                        }
                        3 -> {
                            if (e.pattern == 0) {
                                e.phase -= dt
                                e.fireTimer -= dt
                                if (e.fireTimer <= 0f) {
                                    e.fireTimer = 0.06f
                                    e.spiralA += 0.22f * e.sweep
                                    e.spin--
                                    if (e.spin <= 0) { e.sweep = -e.sweep; e.spin = 70 }
                                    repeat(6) { k ->
                                        val a = e.spiralA + k * (TAU / 6f)
                                        ebullet(e.x, e.y, cos(a) * 195f, sin(a) * 195f, edmg(), "❋", STORM)
                                    }
                                }
                                if (e.phase <= 0f) { e.pattern = 1; e.fireTimer = 1.2f }
                            } else {
                                e.phase -= dt
                                e.fireTimer -= dt
                                if (e.fireTimer <= 0f) {
                                    e.fireTimer = 2.4f
                                    val a = atan2(p.y - e.y, p.x - e.x)
                                    val c = EBullet(e.x + cos(a) * 30f, e.y + sin(a) * 30f,
                                        cos(a) * 72f, sin(a) * 72f, edmg())
                                    c.r = 13f; c.glyph = "🌪️"; c.color = STORM; c.life = 6f
                                    c.pop = 3.2f; c.popN = 8
                                    ebullets.add(c)
                                    ring(e.x, e.y, 12, rng.nextFloat() * TAU, 150f, "❋", STORM)
                                }
                                if (e.phase <= 0f) { e.pattern = 0; e.phase = 20f; e.fireTimer = 0f }
                            }
                        }
                        else -> {
                            if (e.phase > 0f) {
                                e.fireTimer -= dt
                                if (e.fireTimer <= 0f) {
                                    e.fireTimer = 0.07f
                                    e.spiralA += 0.42f
                                    ebullet(e.x, e.y, cos(e.spiralA) * 210f, sin(e.spiralA) * 210f, edmg(), "✕", MAGENTA)
                                    ebullet(e.x, e.y, cos(e.spiralA + PIf) * 210f, sin(e.spiralA + PIf) * 210f, edmg(), "✕", MAGENTA)
                                    e.phase -= 1f
                                }
                            } else {
                                e.fireTimer -= dt
                                if (e.fireTimer <= 0f) when (rng.nextInt(3)) {
                                    0 -> { ring(e.x, e.y, 24, 0f); e.fireTimer = 2.1f }
                                    1 -> { aimedFan(e, 9, 0.3f); e.fireTimer = 2.0f }
                                    else -> { e.phase = 26f; e.fireTimer = 0f }
                                }
                            }
                        }
                    }
                }
            }
            if (d < e.r + p.r * 0.75f && p.invuln <= 0f) {
                val dm = if (e.kind == 8 && e.pattern == 2) e.dmg * 1.25f else e.dmg
                hurt(dm)
            }
        }
        for (i in enemies.indices) {
            val a = enemies[i]
            for (j in i + 1 until enemies.size) {
                val b = enemies[j]
                val dx = b.x - a.x; val dy = b.y - a.y
                val rr = (a.r + b.r) * 0.75f
                val d2 = dx * dx + dy * dy
                if (d2 in 0.01f..rr * rr) {
                    val dd = sqrt(d2); val push = (rr - dd) * 0.5f
                    val nx = dx / dd; val ny = dy / dd
                    a.x -= nx * push; a.y -= ny * push
                    b.x += nx * push; b.y += ny * push
                }
            }
        }
    }

    fun updateEBullets(dt: Float) {
        val p = player
        val it = ebullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.pop > 0f) {
                b.pop -= dt
                if (b.pop <= 0f) {
                    ring(b.x, b.y, b.popN, rng.nextFloat() * TAU, 205f, "❋", STORM)
                    addParticle(b.x, b.y, 0f, 0f, 0.2f, "💥", b.r * 2.4f, 0)
                    it.remove(); continue
                }
            }
            b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt
            if (b.life <= 0f || b.x < -80f || b.x > W + 80f || b.y < -80f || b.y > H + 80f) { it.remove(); continue }
            val dx = b.x - p.x; val dy = b.y - p.y
            val d2 = dx * dx + dy * dy
            val hitR = b.r + p.r * 0.72f
            if (d2 < hitR * hitR && p.invuln <= 0f) { hurt(b.dmg); it.remove(); continue }
            if (!b.grazed && d2 < (p.r * 2.3f) * (p.r * 2.3f)) {
                b.grazed = true
                grazes++
                p.xp += 0.35f * p.luck * (1f + p.luckEq) * diffXp
                addParticle(b.x, b.y, rnd(-40f, 40f), rnd(-40f, 40f), 0.3f, "✧", 16f, CYAN)
            }
        }
    }

    fun updatePickups(dt: Float) {
        val p = player
        val mag = pMagnet()
        for (g in gems) {
            g.t += dt
            val dx = p.x - g.x; val dy = p.y - g.y
            val d = max(0.001f, hypot(dx, dy))
            if (d < mag) {
                val pull = (1f - d / mag) * 1000f + 160f
                g.x += dx / d * pull * dt; g.y += dy / d * pull * dt
            }
            if (d < p.r + 16f) {
                p.xp += g.value * (1f + p.luckEq) * diffXp
                g.t = -999f
                sfx("gem", 5, 0.8f)
            }
        }
        gems.removeAll { it.t <= -999f }
        if (gems.size > 380) p.xp += gems.removeAt(0).value * diffXp

        for (h in hearts) {
            h.t += dt
            val dx = p.x - h.x; val dy = p.y - h.y
            val d = max(0.001f, hypot(dx, dy))
            if (d < mag * 0.7f) { h.x += dx / d * 420f * dt; h.y += dy / d * 420f * dt }
            if (d < p.r + 18f) {
                h.t = -999f
                p.hp = min(p.maxHp, p.hp + 30f)
                ftext(p.x, p.y - 46f, "+30 ❤️", GREEN, 24f)
                sfx("pickup")
            }
        }
        hearts.removeAll { it.t <= -999f }

        for (pw in powers) {
            pw.t += dt
            val dx = p.x - pw.x; val dy = p.y - pw.y
            val d = max(0.001f, hypot(dx, dy))
            if (d < mag * 0.6f) { pw.x += dx / d * 380f * dt; pw.y += dy / d * 380f * dt }
            if (d < p.r + 20f) {
                pw.t = -999f
                rings.add(Ring(pw.x, pw.y, 90f, if (pw.kind == 0) GOLD else RED))
                sfx("pickup")
                if (pw.kind == 0) { p.vacuumT = 3.5f; ftext(p.x, p.y - 52f, "🧲 VACUUM!", GOLD, 26f) }
                else bomb()
            }
        }
        powers.removeAll { it.t <= -999f }

        for (ch in chests) {
            ch.t += dt
            val dx = p.x - ch.x; val dy = p.y - ch.y
            val d = max(0.001f, hypot(dx, dy))
            if (d < mag * 0.8f) { ch.x += dx / d * 300f * dt; ch.y += dy / d * 300f * dt }
            if (d < p.r + 22f) { openChest(ch); break }
        }
        chests.removeAll { it.t <= -999f }
    }

    fun bomb() {
        val p = player
        clearBulletsNear(p.x, p.y, 2500f)
        for (e in enemies) {
            if (e.dead) continue
            e.hp -= pDmg() * 2.5f; e.flash = 0.15f
            if (e.hp <= 0f) killEnemy(e)
        }
        rings.add(Ring(p.x, p.y, 420f, GOLD))
        burst(p.x, p.y, "💥", 12, 34f)
        ftext(p.x, p.y - 70f, "💣 BOOM!", RED, 30f)
        shake = 14f; hitStop = max(hitStop, 0.09f); buzzStrong()
        sfx("nova")
    }

    fun updateFx(dt: Float) {
        player.hurtFlash = max(0f, player.hurtFlash - dt * 2.2f)
        shake = max(0f, shake - dt * 45f)
        val ip = parts.iterator()
        while (ip.hasNext()) {
            val pt = ip.next()
            pt.x += pt.vx * dt; pt.y += pt.vy * dt
            pt.vx *= 1f - dt * 2f; pt.vy *= 1f - dt * 2f
            pt.life -= dt
            if (pt.life <= 0f) ip.remove()
        }
        val it2 = texts.iterator()
        while (it2.hasNext()) { val f = it2.next(); f.life -= dt; if (f.life <= 0f) it2.remove() }
        val ir = rings.iterator()
        while (ir.hasNext()) {
            val r = ir.next()
            r.r += (r.maxR - r.r) * min(1f, dt * 14f); r.life -= dt
            if (r.life <= 0f) ir.remove()
        }
        val ib = bolts.iterator()
        while (ib.hasNext()) { val b = ib.next(); b.life -= dt; if (b.life <= 0f) ib.remove() }
    }

    // ═══════════ spawning & difficulty ═══════════

    fun spawning(dt: Float) {
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            var base = max(0.24f, 1.15f - t / 160f)
            if (ascended) base *= if (nightmare) 0.5f else 0.65f
            spawnTimer = base
            repeat(1 + (t / 55f).toInt().coerceAtMost(4)) { spawnEnemy(pickKind()) }
        }
        burstTimer -= dt
        if (burstTimer <= 0f) {
            burstTimer = when { nightmare -> 18f; ascended -> 22f; else -> 32f }
            val n = 14 + (t / 45f).toInt().coerceAtMost(16)
            val kind = if (t > 60f && rng.nextFloat() < 0.4f) 1 else 0
            for (i in 0 until n) spawnEnemyAt(kind, i * (TAU / n), max(W, H) * 0.75f)
            ftext(player.x, player.y - 90f, "⚠ SWARM INCOMING ⚠", ORANGE, 26f)
        }
        eliteTimer -= dt
        if (eliteTimer <= 0f) {
            eliteTimer = when { nightmare -> 24f; ascended -> 30f; else -> 45f }
            spawnElite()
            if (t > 300f) spawnElite()
        }
        bossTimer -= dt
        if (bossTimer <= 0f) {
            bossTimer = when { nightmare -> 75f; ascended -> 90f; else -> 130f }
            spawnBoss()
        }
        chestTimer -= dt
        if (chestTimer <= 0f) {
            chestTimer = 75f
            chests.add(Chest(player.x + rnd(-240f, 240f), player.y + rnd(-240f, 240f)))
            ftext(player.x, player.y - 70f, "📦 supply drop!", GOLD, 22f)
        }
    }

    fun pickKind(): Int {
        val r = rng.nextFloat()
        return when {
            nightmare && r < 0.12f -> 9
            t > 110f && r < 0.10f -> 6
            t > 60f && r < 0.24f -> 8
            t > 75f && r < 0.36f -> 2
            t > 40f && r < 0.58f -> 3
            t > 18f -> 1
            else -> 0
        }
    }

    fun spawnEnemy(kind: Int) = spawnEnemyAt(kind, rng.nextFloat() * TAU, hypot(W, H) / 2f + 90f)

    fun spawnEnemyAt(kind: Int, a: Float, dist: Float) {
        if (enemies.size > 130) return
        val e = Enemy(player.x + cos(a) * dist, player.y + sin(a) * dist)
        e.wobble = rng.nextFloat() * 6.28f
        val hpMult = 1f + t / 32f + t * t / 26000f
        when (kind) {
            0 -> { e.glyph = "👾"; e.r = 22f; e.hp = 9f; e.speed = 66f + t * 0.35f; e.dmg = 12f; e.xp = 1f }
            1 -> { e.glyph = "🦇"; e.r = 17f; e.hp = 6f; e.speed = 128f + t * 0.4f; e.dmg = 9f; e.xp = 1f }
            2 -> { e.glyph = "💀"; e.r = 30f; e.hp = 48f; e.speed = 40f; e.dmg = 20f; e.xp = 3f }
            3 -> { e.glyph = "🕷️"; e.r = 20f; e.hp = 18f; e.speed = 58f; e.dmg = 10f; e.xp = 2f; e.fireTimer = rnd(0.8f, 2f) }
            6 -> { e.glyph = "🦠"; e.r = 26f; e.hp = 30f; e.speed = 34f; e.dmg = 12f; e.xp = 3f }
            7 -> { e.glyph = "👾"; e.r = 13f; e.hp = 4f; e.speed = 150f + t * 0.3f; e.dmg = 7f; e.xp = 1f }
            8 -> { e.glyph = "🦂"; e.r = 20f; e.hp = 16f; e.speed = 95f + t * 0.2f; e.dmg = 16f; e.xp = 2f }
            9 -> { e.glyph = "🕳️"; e.r = 26f; e.hp = 40f; e.speed = 55f; e.dmg = 18f; e.xp = 5f }
        }
        e.kind = kind
        e.hp *= hpMult * diffHp * ascHp()
        e.maxHp = e.hp
        e.dmg *= (1f + t / 300f) * diffDmg * ascDmg()
        enemies.add(e)
    }

    fun spawnElite() {
        val a = rng.nextFloat() * TAU
        val e = Enemy(player.x + cos(a) * (hypot(W, H) / 2f + 100f), player.y + sin(a) * (hypot(W, H) / 2f + 100f))
        e.glyph = "👺"; e.kind = 4; e.r = 34f
        e.affix = rng.nextInt(4)
        e.hp = 220f * (1f + t / 55f) * diffHp * ascHp()
        if (e.affix == 0) e.hp *= 1.9f
        e.maxHp = e.hp
        e.speed = if (e.affix == 1) 69f else 46f
        e.dmg = 24f * diffDmg * ascDmg(); e.xp = 14f; e.fireTimer = 1.2f
        enemies.add(e)
        ftext(player.x, player.y - 80f, "👺 ${ELITE_AFFIX[e.affix]} ELITE!", ELITE_COLOR[e.affix], 26f)
        shake = max(shake, 6f)
    }

    fun spawnBoss() {
        bossNum++
        val vari = (bossNum - 1) % 4
        val a = rng.nextFloat() * TAU
        val e = Enemy(player.x + cos(a) * (hypot(W, H) / 2f + 120f), player.y + sin(a) * (hypot(W, H) / 2f + 120f))
        e.glyph = BOSS_GLYPH[vari]; e.kind = 5; e.vari = vari; e.r = 52f
        val base = floatArrayOf(900f, 780f, 840f, 860f)[vari]
        e.hp = base * (1f + t / 80f) * (1f + 0.12f * (bossNum - 1)) * diffHp * ascHp()
        e.maxHp = e.hp
        e.speed = floatArrayOf(34f, 40f, 44f, 38f)[vari]
        e.dmg = 30f * diffDmg * ascDmg(); e.xp = 60f; e.fireTimer = 2f
        if (vari == 3) { e.pattern = 0; e.phase = 20f }
        enemies.add(e); boss = e
        ftext(player.x, player.y - 90f, "⚠ ${BOSS_NAMES[vari]} BOSS ⚠", if (vari == 3) STORM else RED, 32f)
        shake = 14f
        sfx("boss")
    }

    // ═══════════ combat helpers ═══════════

    fun edmg() = 11f * (1f + t / 240f) * diffDmg * ascDmg()

    fun ebullet(x: Float, y: Float, vx: Float, vy: Float, dmg: Float, glyph: String = "●", color: Int = RED) {
        if (ebullets.size > 450) return
        val s = (1f + min(0.4f, t / 700f)) *
            (if (nightmare) 1.2f else if (ascended) 1.12f else 1f)
        val b = EBullet(x, y, vx * s, vy * s, dmg)
        b.glyph = glyph; b.color = color
        ebullets.add(b)
    }

    fun ring(x: Float, y: Float, n: Int, offset: Float = 0f, speed: Float = 185f, glyph: String = "◆", color: Int = ORANGE) {
        for (i in 0 until n) {
            val a = offset + i * (TAU / n)
            ebullet(x + cos(a) * 18f, y + sin(a) * 18f, cos(a) * speed, sin(a) * speed, edmg(), glyph, color)
        }
    }

    fun aimedFan(e: Enemy, n: Int, spread: Float) {
        val base = atan2(player.y - e.y, player.x - e.x)
        for (i in 0 until n) {
            val a = if (n == 1) base else base - spread * (n - 1) / 2f + spread * i
            ebullet(e.x + cos(a) * 20f, e.y + sin(a) * 20f, cos(a) * 260f, sin(a) * 260f, edmg(), "●", RED)
        }
    }

    fun fire() {
        val p = player
        val target = nearestEnemy(p.x, p.y) ?: return
        val base = atan2(target.y - p.y, target.x - p.x)
        p.aimA = base
        val n = p.bulletCount
        for (i in 0 until n) {
            val spread = 0.14f
            val a = if (n == 1) base else base - spread * (n - 1) / 2f + spread * i
            spawnBullet(a, 1f)
        }
        if (p.rearGun) spawnBullet(base + PIf, 0.7f)
        for (s in 0 until p.sideGuns) spawnBullet(base + (PIf / 2f) * (if (s == 0) 1f else -1f), 0.6f)
        sfx("shoot", 3, 0.6f)
    }

    fun spawnBullet(a: Float, dmgMul: Float) {
        val p = player
        val lance = evo("w_stream")
        val crit = rng.nextFloat() < (p.critChance + if (lance) 0.15f else 0f)
        val b = Bullet(p.x + cos(a) * 20f, p.y + sin(a) * 20f,
            cos(a) * p.bulletSpeed * (if (lance) 1.4f else 1f),
            sin(a) * p.bulletSpeed * (if (lance) 1.4f else 1f),
            pDmg() * streamDmgMult() * dmgMul * (if (crit) p.critMult else 1f) * (if (lance) 1.8f else 1f))
        b.r = 9f * p.bulletSize * (if (lance) 1.35f else 1f)
        b.glyph = if (lance) "✷" else bulletGlyph()
        b.pierce = if (lance) 99 else p.pierce
        b.homing = p.homing
        b.lance = lance
        b.crit = crit
        b.life = if (lance) 2.8f else 2.4f
        starCount++
        if (lance && starCount % 6 == 0) {
            b.star = true; b.glyph = "✹"; b.r *= 1.7f; b.dmg *= 1.3f; b.pierce = 0
            ftext(p.x, p.y - 60f, "✹ STARFIRE", GOLD, 16f)
        }
        bullets.add(b)
    }

    fun bulletGlyph(): String = when {
        pDmg() >= 90f -> "✵"
        pDmg() >= 60f -> "✸"
        pDmg() >= 35f -> "★"
        pDmg() >= 20f -> "✧"
        else -> "✦"
    }

    fun nearestEnemy(x: Float, y: Float): Enemy? {
        var best: Enemy? = null; var bd = Float.MAX_VALUE
        for (e in enemies) {
            if (e.dead) continue
            val dd = (e.x - x) * (e.x - x) + (e.y - y) * (e.y - y)
            if (dd < bd) { bd = dd; best = e }
        }
        return best
    }

    fun hurt(dmgIn: Float) {
        val p = player
        if (p.invuln > 0f) return
        if (rng.nextFloat() < p.dodgePct) {
            p.invuln = 0.4f
            ftext(p.x, p.y - 54f, "DODGE ✧", CYAN, 26f)
            return
        }
        p.invuln = 0.8f; p.hurtFlash = 1f
        shake = max(shake, 9f)
        hitStop = max(hitStop, 0.05f)
        buzzStrong()
        sfx("hurt")
        val dr = p.armorPts / (p.armorPts + 130f)
        val dmg = dmgIn * (1f - dr)
        if (p.shield > 0) {
            p.shield--
            burst(p.x, p.y, "🛡️", 5, 28f)
            ftext(p.x, p.y - 54f, "BLOCKED 🛡️", CYAN, 24f)
        } else {
            p.hp -= dmg
            dmgTaken += dmg
            ftext(p.x, p.y - 54f, "-${dmg.roundToInt()}", RED, 30f)
        }
        if (p.thorns > 0f) {
            for (e in enemies) {
                if (e.dead) continue
                if (hypot(e.x - p.x, e.y - p.y) < e.r + p.r + 40f) {
                    e.hp -= p.thorns; e.flash = 0.1f
                    if (e.hp <= 0f) killEnemy(e)
                }
            }
            ftext(p.x, p.y - 84f, "🌵${p.thorns.roundToInt()}", ORANGE, 20f)
        }
        if (p.hp <= 0f) deathStart()
    }
    // ═══════════ ADS: revive / double shards ═══════════

    fun deathStart() {
        clearSnapshot()
        sfx("die"); buzzStrong()
        shake = 18f
        burst(player.x, player.y, "💀", 10, 30f)
        // revive offered once per run, never in daily (competitive fairness)
        if (!reviveUsed && !dailyMode && ads?.hasRewarded() == true) {
            reviveOffer = true
            state = State.OVER; overTimer = 0f
        } else gameOver()
    }

    fun chooseRevive() {
        reviveOffer = false
        ads?.showRewarded(
            onReward = { revivePending = true },
            onClosed = { if (revivePending) { revivePending = false; doRevive() } }
        )
    }

    fun doRevive() {
        reviveUsed = true
        val p = player
        p.hp = p.maxHp * 0.6f
        p.invuln = 2.5f
        clearBulletsNear(p.x, p.y, 2500f)
        for (e in enemies) { // shove everything back so you can breathe
            if (e.dead) continue
            val dx = e.x - p.x; val dy = e.y - p.y
            val d = max(1f, hypot(dx, dy))
            if (d < 320f) {
                val k = 320f / d
                e.x = p.x + dx * k; e.y = p.y + dy * k
            }
        }
        rings.add(Ring(p.x, p.y, 300f, GOLD))
        burst(p.x, p.y, "✨", 10, 30f, GOLD)
        ftext(p.x, p.y - 80f, "✨ REVIVED ✨", GOLD, 34f)
        saveRunSnapshot()
        buzzStrong()
        sfx("level")
        state = State.PLAY
    }

    fun giveUp() {
        reviveOffer = false
        gameOver()
    }

    fun chooseDouble() {
        doubleOffer = false
        ads?.showRewarded(
            onReward = { doublePending = true },
            onClosed = {
                if (doublePending) {
                    doublePending = false; doubleUsed = true
                    shards += shardsRun
                    savePrefs()
                    buzz(); sfx("pickup")
                    ftext(player.x, player.y - 60f, "✦ +$shardsRun shards (doubled!)", GOLD, 26f)
                }
            }
        )
    }

    fun pulse() {
        val p = player
        val R = 250f + p.nova * 40f
        for (e in enemies) {
            if (e.dead) continue
            if (hypot(e.x - p.x, e.y - p.y) < R + e.r) {
                e.hp -= pDmg() * 2f; e.flash = 0.15f
                if (e.hp <= 0f) killEnemy(e)
            }
        }
        clearBulletsNear(p.x, p.y, R)
        rings.add(Ring(p.x, p.y, R, CYAN))
        shake = max(shake, 5f)
        sfx("nova", 1, 0.5f)
    }

    fun clearBulletsNear(x: Float, y: Float, R: Float) {
        val it = ebullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (hypot(b.x - x, b.y - y) < R) {
                addParticle(b.x, b.y, rnd(-70f, 70f), rnd(-70f, 70f), 0.3f, "✧", 18f, CYAN)
                it.remove()
            }
        }
    }

    fun killEnemy(e: Enemy, byWasp: Boolean = false) {
        if (e.dead) return
        e.dead = true
        kills++
        sfx("kill", 2, 0.8f)
        val p = player

        comboKills++; comboTimer = 2.5f
        if (comboKills > bestCombo) bestCombo = comboKills
        if (comboKills % 10 == 0) {
            p.xp += comboKills * 0.4f * p.luck * (1f + p.luckEq) * diffXp
            ftext(e.x, e.y - e.r - 20f, "🔥 COMBO ×$comboKills", ORANGE, 24f)
        }

        if (e.freezeT > 0f && evo("w_frost")) { // ★ SHATTER
            val R = 100f
            for (o in enemies) {
                if (o.dead || o === e) continue
                if (hypot(o.x - e.x, o.y - e.y) < R + o.r) {
                    o.hp -= pDmg() * 1.1f; o.flash = 0.1f
                    if (o.hp <= 0f) killEnemy(o)
                }
            }
            rings.add(Ring(e.x, e.y, R, ICE))
            burst(e.x, e.y, "❄️", 7, 22f, ICE)
            repeat(5) {
                val a = rng.nextFloat() * TAU
                val s = Bullet(e.x, e.y, cos(a) * 520f, sin(a) * 520f, pDmg() * 0.5f)
                s.r = 7f; s.glyph = "❄"; s.life = 0.7f
                bullets.add(s)
            }
        }

        if (byWasp && evo("w_hornet")) { // ★ ROYAL JELLY
            repeat(2) { gems.add(Gem(e.x + rnd(-26f, 26f), e.y + rnd(-26f, 26f), e.xp * 0.3f * p.luck)) }
            addParticle(e.x, e.y, 0f, -60f, 0.5f, "🍯", 26f, 0)
        }

        if (evo("w_mine")) { // 💀 PANDEMIC: dying in poison spreads it
            var inPoison = false
            for (f in fireZones) {
                if (f.poison && hypot(e.x - f.x, e.y - f.y) < f.r) { inPoison = true; break }
            }
            if (inPoison && fireZones.size + pendingZones.size < 24)
                pendingZones.add(FireZone(e.x, e.y, 70f, pDmg() * 0.28f, 2.6f, true))
        }

        if (e.kind == 6) { // 🦠 splitter
            val hpMult = 1f + t / 32f + t * t / 26000f
            repeat(3) {
                if (enemies.size + pendingSpawns.size >= 120) return@repeat
                val a = rng.nextFloat() * TAU
                val m = Enemy(e.x + cos(a) * 22f, e.y + sin(a) * 22f)
                m.wobble = rng.nextFloat() * 6.28f
                m.kind = 7; m.glyph = "👾"; m.r = 13f
                m.hp = 4f * hpMult * diffHp * ascHp(); m.maxHp = m.hp
                m.speed = 150f + t * 0.3f; m.dmg = 7f * (1f + t / 300f) * diffDmg * ascDmg(); m.xp = 1f
                pendingSpawns.add(m)
            }
            burst(e.x, e.y, "🦠", 5, 20f)
        }

        if (p.lifesteal > 0f) p.hp = min(p.maxHp, p.hp + p.lifesteal)
        gems.add(Gem(e.x, e.y, e.xp * p.luck))
        when (e.kind) {
            4 -> { // elite loot
                repeat(4) { gems.add(Gem(e.x + rnd(-50f, 50f), e.y + rnd(-50f, 50f), e.xp * p.luck / 4f)) }
                if (rng.nextFloat() < 0.5f) hearts.add(Heart(e.x, e.y))
                if (rng.nextFloat() < 0.55f) chests.add(Chest(e.x, e.y))
                powers.add(Power(rng.nextInt(2), e.x + rnd(-30f, 30f), e.y + rnd(-30f, 30f)))
                clearBulletsNear(e.x, e.y, 280f)
                shake = max(shake, 8f)
            }
            5 -> { // boss loot
                bossesDown++
                repeat(12) { gems.add(Gem(e.x + rnd(-70f, 70f), e.y + rnd(-70f, 70f), e.xp * p.luck / 12f)) }
                hearts.add(Heart(e.x, e.y))
                chests.add(Chest(e.x, e.y))
                clearBulletsNear(e.x, e.y, 400f)
                boss = null; shake = 16f
                hitStop = max(hitStop, 0.12f)
                buzzStrong()
                sfx("nova", 1, 0.9f)
                ftext(e.x, e.y - 60f, "☄ ${BOSS_NAMES[e.vari]} DOWN ☄", GOLD, 32f)
            }
            else -> {
                if (rng.nextFloat() < 0.02f) hearts.add(Heart(e.x, e.y))
                val pr = rng.nextFloat()
                if (pr < 0.012f) powers.add(Power(0, e.x, e.y))
                else if (pr < 0.019f) powers.add(Power(1, e.x, e.y))
            }
        }
        burst(e.x, e.y, e.glyph, 6, e.r * 0.8f)
        addParticle(e.x, e.y, 0f, 0f, 0.22f, "💥", e.r * 2.2f, 0)
    }

    fun burst(x: Float, y: Float, glyph: String, n: Int, size: Float, color: Int = 0) {
        repeat(n) {
            val a = rng.nextFloat() * TAU
            val spd = rnd(40f, 190f)
            addParticle(x, y, cos(a) * spd, sin(a) * spd, rnd(0.25f, 0.6f),
                glyph, size * rnd(0.7f, 1.3f), color)
        }
    }

    fun hitSpark(x: Float, y: Float, crit: Boolean) {
        repeat(2) {
            val a = rng.nextFloat() * TAU
            val spd = rnd(30f, 120f)
            addParticle(x, y, cos(a) * spd, sin(a) * spd, 0.2f,
                if (crit) "✶" else "✧", 16f, if (crit) GOLD else CYAN)
        }
    }

    // ═══════════ ARMOR ═══════════

    fun rollArmor(): ArmorPiece {
        val r = rollRarity()
        val slot = rng.nextInt(5)
        val nAff = 1 + r
        val scale = 1f + t / 210f
        val aff = HashMap<String, Float>()
        val pool = AFFIX_TPLS.toMutableList()
        pool.shuffle(rng)
        repeat(nAff) {
            if (pool.isEmpty()) return@repeat
            val a = pool.removeAt(0)
            val v = a.base * scale * (0.85f + rng.nextFloat() * 0.35f) * (1f + r * 0.3f)
            aff[a.id] = (aff[a.id] ?: 0f) + v
        }
        return ArmorPiece(slot, r, aff)
    }

    fun openChest(ch: Chest) {
        ch.t = -999f
        joyDown = false
        chestsOpened++
        pendingArmor = rollArmor()
        pendingOld = equipment[pendingArmor!!.slot]
        rings.add(Ring(ch.x, ch.y, 130f, GOLD))
        burst(ch.x, ch.y, "📦", 6, 26f, GOLD)
        shake = max(shake, 4f)
        buzz()
        sfx("chest")
        state = State.ARMOR
    }

    fun chooseArmor(equipNew: Boolean) {
        val pa = pendingArmor
        if (pa == null) { state = State.PLAY; return }
        val p = player
        if (equipNew) {
            equipment[pa.slot] = pa
            recalcEquipment()
            if (pendingOld != null) p.hp = min(p.maxHp, p.hp + 12f)
            ftext(p.x, p.y - 62f,
                "${RARITY_GLYPH[pa.rarity]} ${SLOT_NAMES[pa.slot]} equipped!", RARITY_COLOR[pa.rarity], 26f)
            ftext(p.x, p.y - 34f, affixSummary(pa), Color.WHITE, 15f)
            rings.add(Ring(p.x, p.y, 120f, GOLD))
            burst(p.x, p.y, "✨", 6, 24f, GOLD)
            sfx("pickup")
        } else {
            val heal = 25f + pa.rarity * 15f
            p.hp = min(p.maxHp, p.hp + heal)
            ftext(p.x, p.y - 50f, "salvaged +${heal.roundToInt()} ❤️", GREEN, 22f)
        }
        pendingArmor = null; pendingOld = null
        state = State.PLAY
        saveRunSnapshot()
    }

    fun affixSummary(pc: ArmorPiece): String {
        val sb = StringBuilder()
        for (a in AFFIX_TPLS) {
            val v = pc.affixes[a.id] ?: continue
            sb.append(a.icon).append(fmtVal(v, a.isPct)).append(' ')
        }
        return sb.toString().trim()
    }

    fun fmtVal(v: Float, pct: Boolean): String =
        if (pct) "${v.roundToInt()}%"
        else if (v < 10f) String.format(Locale.US, "%.1f", v)
        else "${v.roundToInt()}"

    // ═══════════ PROCEDURAL LEVEL-UP ═══════════

    fun rollRarity(): Int {
        val luckT = min(1f, t / 480f)
        val w = floatArrayOf(
            58f * (1f - 0.3f * luckT), 26f * (1f + 0.2f * luckT),
            12f * (1f + 0.8f * luckT), 4f * (1f + 1.6f * luckT))
        var r = rng.nextFloat() * w.sum()
        for (i in w.indices) { r -= w[i]; if (r <= 0f) return i }
        return 0
    }

    fun evolutionReady(): WeaponTpl? = weaponTpls.firstOrNull { w ->
        w.id !in banished &&
            (stacks[w.id] ?: 0) >= w.maxLvl && (stacks[w.pairId] ?: 0) >= w.pairReq && !evo(w.id)
    }

    fun rollUpgrades() {
        upgrades.clear()
        var slots = 3
        val evoW = evolutionReady()
        if (evoW != null) {
            upgrades.add(Upgrade(evoW.id, evoW.evoName, evoW.evoIcon, evoW.evoDesc, 3, 2,
                "★ PASSIVE ${evoW.pName} — ${evoW.pDesc}") {
                evolved.add(evoW.id)
                shake = 12f
            })
            slots = 2
        }
        val statPool = templates.filter {
            (stacks[it.id] ?: 0) < it.max && it.avail(player) && it.id !in banished
        }.toMutableList()
        val ownedWeapons = weaponTpls.count { (stacks[it.id] ?: 0) > 0 }
        val wepPool = weaponTpls.filter { w ->
            w.id !in banished && run {
                val lv = stacks[w.id] ?: 0
                lv < w.maxLvl && (lv > 0 || ownedWeapons < weaponSlots())
            }
        }.toMutableList()
        var feast = false
        repeat(slots) {
            val useWep = wepPool.isNotEmpty() && (statPool.isEmpty() || rng.nextFloat() < 0.45f)
            if (useWep) {
                val w = wepPool.random(rng); wepPool.remove(w)
                val newLvl = (stacks[w.id] ?: 0) + 1
                upgrades.add(Upgrade(w.id, w.name, w.icon,
                    (if (newLvl == 1) "NEW — " else "") + w.lvlDesc(newLvl), 1, 1,
                    "MAX + ${w.pairIcon}×${w.pairReq} → ${w.evoIcon} ${w.evoName}") {
                    stacks[w.id] = newLvl
                })
            } else if (statPool.isNotEmpty()) {
                val rar = rollRarity()
                val tpl = statPool.random(rng); statPool.remove(tpl)
                val m = RARITY_MULT[rar] * (0.85f + rng.nextFloat() * 0.3f)
                upgrades.add(Upgrade(tpl.id, RARITY_PREFIX[rar] + tpl.name, tpl.icon,
                    tpl.desc(player, m), rar, 0, "") { tpl.apply(player, m) })
            } else if (!feast) {
                feast = true
                upgrades.add(Upgrade("feast", "Feast", "🍗", "heal 50% of max HP", 0, 0, "") {
                    player.hp = min(player.maxHp, player.hp + player.maxHp * 0.5f)
                })
            }
        }
    }

    fun levelUp() {
        val p = player
        p.xp -= p.xpNext
        p.level++
        p.xpNext = 5f + p.level * 3f + p.level * p.level * 0.4f
        p.hp = min(p.maxHp, p.hp + p.maxHp * 0.08f)
        p.vacuumT = max(p.vacuumT, 1f)
        joyDown = false
        rollUpgrades()
        burst(p.x, p.y, "⬆", 6, 26f, GOLD)
        ftext(p.x, p.y - 60f, "LEVEL UP ⬆", GOLD, 30f)
        buzz()
        sfx("level")
        state = State.LEVELUP
    }

    fun choose(i: Int) {
        val u = upgrades.getOrNull(i) ?: return
        u.apply()
        if (u.kind == 0) stacks[u.id] = (stacks[u.id] ?: 0) + 1
        when (u.kind) {
            2 -> {
                discovered.add(u.id); savePrefs()
                ftext(player.x, player.y - 70f, "★ EVOLVED: ${u.icon} ${u.title} ★", GOLD, 30f)
                val wt = weaponTpls.firstOrNull { it.id == u.id }
                if (wt != null) ftext(player.x, player.y - 102f, "★ PASSIVE: ${wt.pName} ★", CYAN, 20f)
                burst(player.x, player.y, "★", 10, 30f, GOLD)
                hitStop = max(hitStop, 0.1f)
                buzzStrong()
                sfx("evolve")
            }
            1 -> { ftext(player.x, player.y - 70f, "${u.icon} Lv up", CYAN, 24f); sfx("pickup", 1, 0.7f) }
            else -> { ftext(player.x, player.y - 70f, "▲ ${u.title}", RARITY_COLOR[u.rarity], 28f); sfx("pickup", 1, 0.7f) }
        }
        val er = evolutionReady()
        if (er != null && er.id !in notifiedEvo) {
            notifiedEvo.add(er.id)
            ftext(player.x, player.y - 130f,
                "★ EVOLUTION READY: ${er.evoIcon} ${er.evoName} ★", GOLD, 22f)
        }
        upgrades.clear()
        state = if (player.xp >= player.xpNext) { levelUp(); State.LEVELUP } else State.PLAY
        saveRunSnapshot()
    }

    fun gameOver() {
        best = max(best, t)
        var mult = diffMult
        if (ascended) mult *= 1.5f
        if (nightmare) mult *= 1.25f
        shardsRun = ((kills / 8f + (t / 60f) * 6f) * mult).roundToInt().coerceAtLeast(1)
        shards += shardsRun
        totalKills += kills
        scoreRun = ((kills * 10 + bestCombo * 50 + bossesDown * 500 + t.toInt() * 2 + grazes * 5) * mult).roundToInt()
        bestScore = max(bestScore, scoreRun)
        if (dailyMode && scoreRun > dailyBest())
            prefs.edit().putInt(dailyKey(), scoreRun).apply()
        savePrefs()
        clearSnapshot()
        checkAchv()
        deaths++
        prefs.edit().putInt("deaths", deaths).apply()
        // ✦×2 rewarded offer (once per run, needs ad inventory)
        doubleOffer = !doubleUsed && shardsRun > 0 && ads?.hasRewarded() == true
        // interstitial every 3rd death, frequency-capped
        if (deaths % 3 == 0) ads?.maybeInterstitial()
        state = State.OVER
        overTimer = 0f
        burst(player.x, player.y, "💀", 10, 30f)
    }

    fun pickStarter(i: Int) {
        val w = starterOffers.getOrNull(i) ?: return
        stacks[w.id] = 1
        ftext(player.x, player.y - 60f, "${w.icon} ${w.name} equipped!", CYAN, 24f)
        rings.add(Ring(player.x, player.y, 110f, CYAN))
        sfx("pickup")
        state = State.PLAY
        saveRunSnapshot()
    }

    fun startGame(daily: Boolean = false) {
        dailyMode = daily
        if (!restoring) clearSnapshot()
        rng = if (daily) Random(dailySeed().toLong()) else Random(System.nanoTime())
        t = 0f; kills = 0
        grazes = 0; dmgTaken = 0f; chestsOpened = 0
        comboKills = 0; comboTimer = 0f; bestCombo = 0
        shardsRun = 0; scoreRun = 0
        rerolls = 1 + (metaLv["insight"] ?: 0)
        bossNum = 0; bossesDown = 0
        ascended = false; ascT = 0f; nightmare = false
        reviveUsed = false; reviveOffer = false; revivePending = false
        doubleUsed = false; doubleOffer = false; doublePending = false
        musicRate = 1f
        hitStop = 0f
        bullets.clear(); ebullets.clear(); enemies.clear(); pendingSpawns.clear()
        gems.clear(); hearts.clear(); chests.clear(); powers.clear(); mines.clear()
        meteors.clear(); fireZones.clear(); pendingZones.clear(); bolts.clear()
        parts.clear(); texts.clear(); rings.clear(); stacks.clear(); upgrades.clear()
        evolved.clear(); notifiedEvo.clear(); banished.clear()
        for (i in 0..4) equipment[i] = null
        boss = null
        spawnTimer = 1.1f; eliteTimer = 25f; bossTimer = 120f; burstTimer = 34f; chestTimer = 55f
        starCount = 0; whirlTimer = 4f
        pendingArmor = null; pendingOld = null
        metaOpen = false; achvOpen = false
        banishes = 1 + (metaLv["ban"] ?: 0)
        val p = Player()
        p.baseMaxHp = 100f * (1f + 0.10f * (metaLv["hp"] ?: 0))
        p.maxHp = p.baseMaxHp; p.hp = p.baseMaxHp
        p.damage = 12f * (1f + 0.06f * (metaLv["dmg"] ?: 0))
        p.magnet = 130f * (1f + 0.15f * (metaLv["mag"] ?: 0))
        p.x = W / 2f; p.y = H / 2f; p.invuln = 1.5f
        player = p
        recalcEquipment()
        starterOffers = weaponTpls.toMutableList().also { it.shuffle(rng) }.take(3)
        shake = 0f
        state = State.STARTER
    }

    // ═══════════ run snapshot (⏸ continue) ═══════════

    fun hasSnapshot() = prefs.getBoolean("snap", false)
    fun clearSnapshot() { prefs.edit().putBoolean("snap", false).apply() }

    fun saveRunSnapshot() {
        if (state != State.PLAY) return
        val p = player
        val e = prefs.edit()
        fun f(k: String, v: Float) = e.putFloat(k, v)
        f("s_t", t)
        e.putInt("s_kills", kills).putInt("s_lvl", p.level)
        f("s_xp", p.xp); f("s_next", p.xpNext); f("s_hp", p.hp); f("s_bhp", p.baseMaxHp)
        f("s_dmg_p", p.damage); f("s_rate", p.fireRate); f("s_speed", p.speed)
        f("s_mag", p.magnet); f("s_crit", p.critChance); f("s_critm", p.critMult)
        f("s_ls", p.lifesteal); f("s_regen", p.regen)
        e.putInt("s_shield", p.shield).putInt("s_shmax", p.shieldMax)
        e.putBoolean("s_rear", p.rearGun).putInt("s_side", p.sideGuns)
        f("s_luck", p.luck).putInt("s_nova", p.nova)
        e.putInt("s_count", p.bulletCount).putInt("s_pierce", p.pierce)
        f("s_bsize", p.bulletSize); f("s_home", p.homing)
        e.putString("s_stacks", stacks.entries.joinToString(",") { "${it.key}:${it.value}" })
        e.putString("s_evo", evolved.joinToString(","))
        e.putString("s_ban", banished.joinToString(","))
        e.putString("s_eq", equipment.mapIndexed { i, pc ->
            pc?.let { "$i;${it.rarity};" +
                it.affixes.entries.joinToString(",") { a -> "${a.key}:${a.value}" } } ?: ""
        }.joinToString("|"))
        e.putBoolean("s_asc", ascended).putBoolean("s_night", nightmare)
        f("s_asct", ascT)
        e.putInt("s_bossn", bossNum).putInt("s_bossd", bossesDown)
        e.putInt("s_rr", rerolls).putInt("s_ban_n", banishes)
        e.putInt("s_combo", bestCombo).putInt("s_graze", grazes)
        f("s_dt", dmgTaken)
        e.putInt("s_ch", chestsOpened).putInt("s_star", starCount)
        e.putBoolean("s_daily", dailyMode)
        e.putBoolean("s_rev", reviveUsed)
        e.putBoolean("snap", true)
        e.apply()
    }

    fun restoreRun() {
        if (!hasSnapshot()) return
        restoring = true
        startGame(prefs.getBoolean("s_daily", false))
        restoring = false
        val p = player
        t = prefs.getFloat("s_t", 0f)
        kills = prefs.getInt("s_kills", 0)
        p.level = prefs.getInt("s_lvl", 1)
        p.xp = prefs.getFloat("s_xp", 0f); p.xpNext = prefs.getFloat("s_next", 6f)
        p.hp = prefs.getFloat("s_hp", p.maxHp); p.baseMaxHp = prefs.getFloat("s_bhp", 100f)
        p.damage = prefs.getFloat("s_dmg_p", 12f); p.fireRate = prefs.getFloat("s_rate", 1.7f)
        p.speed = prefs.getFloat("s_speed", 330f); p.magnet = prefs.getFloat("s_mag", 130f)
        p.critChance = prefs.getFloat("s_crit", 0.05f); p.critMult = prefs.getFloat("s_critm", 2f)
        p.lifesteal = prefs.getFloat("s_ls", 0f); p.regen = prefs.getFloat("s_regen", 0f)
        p.shield = prefs.getInt("s_shield", 0); p.shieldMax = prefs.getInt("s_shmax", 0)
        p.rearGun = prefs.getBoolean("s_rear", false); p.sideGuns = prefs.getInt("s_side", 0)
        p.luck = prefs.getFloat("s_luck", 1f); p.nova = prefs.getInt("s_nova", 0)
        p.bulletCount = prefs.getInt("s_count", 1); p.pierce = prefs.getInt("s_pierce", 0)
        p.bulletSize = prefs.getFloat("s_bsize", 1f); p.homing = prefs.getFloat("s_home", 0f)
        stacks.clear()
        for (s in prefs.getString("s_stacks", "")!!.split(",")) {
            if (!s.contains(':')) continue
            val kv = s.split(":", limit = 2)
            stacks[kv[0]] = kv[1].toIntOrNull() ?: 1
        }
        evolved.clear()
        evolved.addAll(prefs.getString("s_evo", "")!!.split(",").filter { it.isNotEmpty() })
        banished.clear()
        banished.addAll(prefs.getString("s_ban", "")!!.split(",").filter { it.isNotEmpty() })
        for (i in 0..4) equipment[i] = null
        prefs.getString("s_eq", "")!!.split("|").forEach { seg ->
            if (seg.isBlank()) return@forEach
            val parts = seg.split(";", limit = 3)
            val slot = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
            if (slot !in 0..4) return@forEach
            val aff = HashMap<String, Float>()
            parts.getOrNull(2)?.split(",")?.forEach { a ->
                val kv = a.split(":", limit = 2)
                val v = kv.getOrNull(1)?.toFloatOrNull() ?: return@forEach
                aff[kv[0]] = v
            }
            equipment[slot] = ArmorPiece(slot, parts.getOrNull(1)?.toIntOrNull() ?: 0, aff)
        }
        ascended = prefs.getBoolean("s_asc", false)
        nightmare = prefs.getBoolean("s_night", false)
        ascT = prefs.getFloat("s_asct", 0f)
        bossNum = prefs.getInt("s_bossn", 0); bossesDown = prefs.getInt("s_bossd", 0)
        rerolls = prefs.getInt("s_rr", 1); banishes = prefs.getInt("s_ban_n", 1)
        bestCombo = prefs.getInt("s_combo", 0); grazes = prefs.getInt("s_graze", 0)
        dmgTaken = prefs.getFloat("s_dt", 0f); chestsOpened = prefs.getInt("s_ch", 0)
        starCount = prefs.getInt("s_star", 0)
        reviveUsed = prefs.getBoolean("s_rev", false)
        musicRate = when { nightmare -> 1.25f; ascended -> 1.12f; else -> 1f }
        recalcEquipment()
        timeSec = -1; hpKey = -1
        state = State.PLAY
        player.invuln = 1.5f
        saveRunSnapshot()
    }

    // ═══════════ rendering ═══════════

    fun render(c: Canvas) {
        ads?.showBanner(state == State.MENU || state == State.OVER)
        c.drawColor(Color.rgb(10, 12, 24))
        drawBg(c)
        c.save()
        if (fxOn && shake > 0.3f) c.translate(rnd(-shake, shake), rnd(-shake, shake))
        drawWorld(c)
        c.restore()
        if (state == State.PLAY && nightmare) {
            fill.style = Paint.Style.FILL
            val a = min(60f, 14f + (ascT - 600f).coerceAtLeast(0f) * 4f).toInt().coerceIn(0, 120)
            fill.color = Color.argb(a, 90, 20, 140)
            c.drawRect(0f, 0f, W, H, fill)
        } else if (state == State.PLAY && ascended) {
            fill.style = Paint.Style.FILL
            val a = min(44f, 10f + ascT * 6f).toInt().coerceIn(0, 90)
            fill.color = Color.argb(a, 110, 60, 200)
            c.drawRect(0f, 0f, W, H, fill)
        }
        if (fxOn && player.hurtFlash > 0f) {
            fill.style = Paint.Style.FILL
            fill.color = Color.argb((player.hurtFlash * 60f).toInt().coerceIn(0, 160), 255, 30, 60)
            c.drawRect(0f, 0f, W, H, fill)
        }
        val hpf = player.hp / player.maxHp
        if (state == State.PLAY && hpf < 0.3f) {
            val a = ((0.3f - hpf) / 0.3f * (70f + 50f * (0.5f + 0.5f * sin(animT * 6f)))).toInt().coerceIn(0, 200)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 26f
            fill.color = Color.argb(a, 255, 30, 50)
            c.drawRect(6f, 6f, W - 6f, H - 6f, fill)
        }
        if (state == State.PLAY) drawIndicators(c)
        when (state) {
            State.MENU -> { drawMenu(c); if (metaOpen) drawMeta(c); if (achvOpen) drawAchv(c) }
            State.STARTER -> drawStarter(c)
            State.PLAY -> drawHud(c)
            State.LEVELUP -> { drawHud(c); drawLevelUp(c) }
            State.ARMOR -> drawArmor(c)
            State.PAUSE -> drawPause(c)
            State.CODEX -> drawCodex(c)
            State.OVER -> { drawHud(c); drawOver(c) }
        }
        drawAchvBanner(c)
    }

    fun drawAchvBanner(c: Canvas) {
        if (achvQueue.isEmpty()) return
        val a = achvTpls.firstOrNull { it.id == achvQueue.first() } ?: return
        val bw = min(W - 40f, 400f)
        val r = RectF(W / 2f - bw / 2f, safeTop + 20f, W / 2f + bw / 2f, safeTop + 78f)
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(235, 30, 26, 10)
        c.drawRoundRect(r, 14f, 14f, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 3f
        fill.color = GOLD
        c.drawRoundRect(r, 14f, 14f, fill)
        drawGlyph(c, "🏆", r.left + 34f, r.centerY(), 34f, 0)
        lp.textSize = 13f; lp.color = GOLD
        c.drawText("ACHIEVEMENT UNLOCKED", r.left + 66f, r.top + 21f, lp)
        lp.textSize = 17f; lp.color = Color.WHITE
        c.drawText("${a.icon} ${a.name}", r.left + 66f, r.top + 44f, lp)
    }

    fun drawBg(c: Canvas) {
        val p = player
        fill.style = Paint.Style.FILL
        val spacing = 120f
        var ox = (-(p.x * 0.12f)) % spacing; if (ox > 0f) ox -= spacing
        var oy = (-(p.y * 0.12f)) % spacing; if (oy > 0f) oy -= spacing
        fill.color = Color.argb(26, 110, 130, 220)
        fill.textSize = 30f
        var y = oy
        while (y < H + spacing) {
            var x = ox
            while (x < W + spacing) { c.drawText("·", x, y, fill); x += spacing }
            y += spacing
        }
        for (s in stars) {
            val spanX = W + 120f; val spanY = H + 120f
            val sx = ((s.x - p.x * 0.3f) % spanX + spanX) % spanX - 60f
            val sy = ((s.y - p.y * 0.3f) % spanY + spanY) % spanY - 60f
            val a = (40f + 55f * (0.5f + 0.5f * sin(animT * 2f + s.phase))).toInt()
            drawGlyph(c, s.glyph, sx, sy, 22f, Color.argb(a, 150, 170, 255))
        }
    }

    fun drawWorld(c: Canvas) {
        val p = player
        for (g in gems) {
            val bob = sin(g.t * 5f) * 3f
            if (g.value >= 20f) drawGlyph(c, "💎", g.x, g.y + bob, 36f, 0)
            else if (g.value >= 4f) drawGlyph(c, "🔹", g.x, g.y + bob, 30f, 0)
            else drawGlyph(c, "◈", g.x, g.y + bob, 26f, CYAN)
        }
        for (h in hearts) drawGlyph(c, "❤️", h.x, h.y + sin(h.t * 5f) * 3f, 34f, 0)
        for (ch in chests) {
            val bob = sin(ch.t * 5f) * 3f
            fill.style = Paint.Style.STROKE; fill.strokeWidth = 2.5f
            fill.color = Color.argb((90f + 70f * (0.5f + 0.5f * sin(ch.t * 6f))).toInt(), 255, 213, 79)
            c.drawCircle(ch.x, ch.y + bob, 34f, fill)
            drawGlyph(c, "📦", ch.x, ch.y + bob, 40f, 0)
        }
        for (pw in powers) {
            val bob = sin(pw.t * 5f) * 3f
            fill.style = Paint.Style.STROKE; fill.strokeWidth = 2.5f
            fill.color = if (pw.kind == 0) Color.argb(170, 255, 213, 79) else Color.argb(170, 255, 90, 90)
            c.drawCircle(pw.x, pw.y + bob, 26f, fill)
            drawGlyph(c, if (pw.kind == 0) "🧲" else "💣", pw.x, pw.y + bob, 32f, 0)
        }
        for (m in mines) {
            val armed = m.arm <= 0f
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 2f
            fill.color = if (armed)
                Color.argb((120f + 80f * sin(animT * 8f)).toInt().coerceIn(0, 255), 255, 200, 90)
            else Color.argb(90, 150, 150, 160)
            c.drawCircle(m.x, m.y, 26f, fill)
            drawGlyph(c, "🧨", m.x, m.y, 30f, 0)
        }
        for (f in fireZones) {
            val lf = (1f - f.t / f.dur).coerceIn(0f, 1f)
            fill.style = Paint.Style.FILL
            fill.color = if (f.poison) Color.argb((70f * lf).toInt().coerceIn(0, 255), 90, 230, 90)
            else Color.argb((70f * lf).toInt().coerceIn(0, 255), 255, 110, 30)
            c.drawCircle(f.x, f.y, f.r, fill)
            val g = if (f.poison) "☠️" else "🔥"
            for (i in 0 until 5) {
                val a = animT * 2f + i * (TAU / 5f)
                drawGlyph(c, g, f.x + cos(a) * f.r * 0.7f, f.y + sin(a) * f.r * 0.7f,
                    24f * lf + 10f, 0)
            }
        }
        for (m in meteors) {
            fill.style = Paint.Style.FILL
            fill.color = Color.argb(60, 255, 150, 60)
            c.drawCircle(m.tx, m.drawY(), 26f, fill)
            drawGlyph(c, "☄️", m.tx, m.drawY(), 38f, 0)
        }

        for (e in enemies) {
            if (e.kind == 4) {
                fill.style = Paint.Style.STROKE; fill.strokeWidth = 3f
                fill.color = Color.argb(150, Color.red(ELITE_COLOR[e.affix]),
                    Color.green(ELITE_COLOR[e.affix]), Color.blue(ELITE_COLOR[e.affix]))
                c.drawCircle(e.x, e.y, e.r + 7f, fill)
                drawGlyph(c, ELITE_ICON[e.affix], e.x, e.y - e.r - 18f, 20f, 0)
            }
            if (e.kind == 9) {
                fill.style = Paint.Style.STROKE; fill.strokeWidth = 2f
                fill.color = Color.argb(70, 160, 80, 255)
                c.drawCircle(e.x, e.y, 130f + 8f * sin(animT * 3f), fill)
            }
            if (e.slowT > 0f && e.freezeT <= 0f) {
                fill.style = Paint.Style.FILL
                fill.color = Color.argb(60, 140, 210, 255)
                c.drawCircle(e.x, e.y, e.r * 1.1f, fill)
            }
            drawGlyph(c, e.glyph, e.x, e.y + sin(e.wobble) * 3f, e.r * 2.1f, 0)
            if (e.kind == 8 && e.pattern == 1) {
                fill.style = Paint.Style.STROKE; fill.strokeWidth = 3f
                fill.color = Color.argb(180, 255, 70, 70)
                c.drawCircle(e.x, e.y, e.r + 8f + 4f * sin(animT * 20f), fill)
                drawGlyph(c, "❗", e.x, e.y - e.r - 16f, 24f, RED)
            }
            if (e.freezeT > 0f) drawGlyph(c, "❄", e.x, e.y - e.r - 14f, 22f, ICE)
            if (e.flash > 0f) {
                fill.style = Paint.Style.FILL
                fill.color = Color.argb((e.flash / 0.15f * 130f).toInt().coerceIn(0, 255), 255, 255, 255)
                c.drawCircle(e.x, e.y, e.r * 1.05f, fill)
            }
            if ((e.kind in 2..4 || e.kind == 6 || e.kind == 9) && e.hp < e.maxHp) {
                val bw = e.r * 2.2f
                fill.style = Paint.Style.FILL
                fill.color = Color.argb(170, 0, 0, 0)
                c.drawRect(e.x - bw / 2f, e.y - e.r - 16f, e.x + bw / 2f, e.y - e.r - 9f, fill)
                fill.color = GREEN
                c.drawRect(e.x - bw / 2f, e.y - e.r - 16f, e.x - bw / 2f + bw * (e.hp / e.maxHp).coerceIn(0f, 1f), e.y - e.r - 9f, fill)
            }
        }
        for (b in ebullets) {
            drawGlyph(c, b.glyph, b.x, b.y, b.r * 2.2f, b.color)
            if (b.pop > 0f) {
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = 2.5f
                fill.color = Color.argb(170, Color.red(b.color), Color.green(b.color), Color.blue(b.color))
                c.drawCircle(b.x, b.y, b.r * 2.2f + 7f * sin(animT * 12f), fill)
            }
        }

        for (b in bolts) {
            val a = (b.life / 0.22f).coerceIn(0f, 1f)
            val path = Path()
            path.moveTo(b.pts[0], b.pts[1])
            for (i in 1 until b.pts.size / 2) path.lineTo(b.pts[i * 2], b.pts[i * 2 + 1])
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 8f
            fill.color = Color.argb((a * 90f).toInt().coerceIn(0, 255), 120, 220, 255)
            c.drawPath(path, fill)
            fill.strokeWidth = 2.5f
            fill.color = Color.argb((a * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
            c.drawPath(path, fill)
        }

        for (b in bullets)
            drawGlyph(c, b.glyph, b.x, b.y, b.r * (if (b.crit) 3.1f else if (b.lance || b.star) 2.9f else 2.4f),
                if (b.crit || b.lance || b.star || b.boom) GOLD else CYAN)

        for (r in rings) {
            val lf = (r.life / 0.45f).coerceIn(0f, 1f)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 3f + 6f * lf
            fill.color = Color.argb((lf * 190f).toInt().coerceIn(0, 255),
                Color.red(r.color), Color.green(r.color), Color.blue(r.color))
            c.drawCircle(r.x, r.y, r.r, fill)
        }

        val hLvl = stacks["w_halo"] ?: 0
        if (hLvl > 0) {
            val blade = evo("w_halo")
            val count = hLvl + if (blade) 2 else 0
            val orbDist = if (blade) 118f else 92f
            for (i in 0 until count) {
                val a = animT * (if (blade) 3.4f else 2.6f) + i * (TAU / count)
                drawGlyph(c, if (blade) "⚔️" else "✨", p.x + cos(a) * orbDist, p.y + sin(a) * orbDist,
                    if (blade) 38f else 30f, 0)
            }
        }

        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 2.5f
        fill.color = Color.argb(26, 160, 225, 255)
        val ax = cos(p.aimA); val ay = sin(p.aimA)
        c.drawLine(p.x + ax * (p.r + 12f), p.y + ay * (p.r + 12f),
            p.x + ax * 118f, p.y + ay * 118f, fill)

        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 2f
        fill.color = if (p.vacuumT > 0f) Color.argb(60, 255, 213, 79) else Color.argb(16, 130, 200, 255)
        c.drawCircle(p.x, p.y, pMagnet(), fill)
        val blink = p.invuln > 0f && ((animT * 18f).toInt() % 2 == 0)
        if (!blink) drawGlyph(c, "🧙", p.x, p.y, 60f, 0)
        if (p.tailwindT > 0f) drawGlyph(c, "💨", p.x, p.y + 36f, 20f, 0)
        fill.color = if (p.shield > 0) Color.argb(150, 120, 220, 255) else Color.argb(40, 120, 220, 255)
        fill.strokeWidth = if (p.shield > 0) 3.5f else 2f
        c.drawCircle(p.x, p.y, p.r + 8f, fill)

        for (pt in parts) {
            val lf = (pt.life / pt.maxLife).coerceIn(0f, 1f)
            val size = pt.size * (0.35f + 0.65f * lf)
            if (pt.color == 0) drawGlyph(c, pt.glyph, pt.x, pt.y, size, 0)
            else drawGlyph(c, pt.glyph, pt.x, pt.y, size,
                Color.argb((lf * 255f).toInt(), Color.red(pt.color), Color.green(pt.color), Color.blue(pt.color)))
        }
        for (ft in texts) {
            val prog = 1f - ft.life / 0.9f
            val a = (ft.life / 0.9f * 255f).toInt().coerceIn(0, 255)
            drawGlyph(c, ft.text, ft.x, ft.y - prog * 44f, ft.size,
                Color.argb(a, Color.red(ft.color), Color.green(ft.color), Color.blue(ft.color)))
        }
    }

    fun drawIndicators(c: Canvas) {
        val p = player
        val m = 30f
        fun ind(x: Float, y: Float, glyph: String, col: Int) {
            if (x in 0f..W && y in 0f..H) return
            val dx = x - p.x; val dy = y - p.y
            val tx = if (dx > 0f) (W - m - p.x) / dx else if (dx < 0f) (m - p.x) / dx else Float.MAX_VALUE
            val ty = if (dy > 0f) (H - m - p.y) / dy else if (dy < 0f) (m - p.y) / dy else Float.MAX_VALUE
            val tt = minOf(tx, ty).coerceAtLeast(0.001f)
            val ix = p.x + dx * tt; val iy = p.y + dy * tt
            val arrow = when {
                ty <= tx && dy < 0f -> "▲"
                ty <= tx -> "▼"
                dx < 0f -> "◀"
                else -> "▶"
            }
            drawGlyph(c, arrow, ix, iy, 22f, col)
            drawGlyph(c, glyph, ix, iy - 22f, 14f, col)
        }
        for (e in enemies) {
            if (e.dead) continue
            if (e.kind == 5) ind(e.x, e.y, e.glyph, if (e.vari == 3) STORM else RED)
            else if (e.kind == 4) ind(e.x, e.y, "👺", ORANGE)
            else if (e.kind == 9) ind(e.x, e.y, "🕳️", VOID)
        }
        for (ch in chests) ind(ch.x, ch.y, "📦", GOLD)
    }

    fun drawHud(c: Canvas) {
        val p = player
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(160, 0, 0, 0)
        c.drawRect(0f, safeTop, W, safeTop + 14f, fill)
        fill.color = Color.rgb(120, 220, 255)
        c.drawRect(0f, safeTop, W * (p.xp / p.xpNext).coerceIn(0f, 1f), safeTop + 14f, fill)

        val badge = when {
            nightmare -> "  ☠☠"
            ascended -> "  ⚡"
            dailyMode -> "  📅"
            else -> ""
        }
        drawGlyph(c, "⏱ ${fmtTime(t)}   ☠ $kills   ⬆ Lv ${p.level}$badge",
            W / 2f, safeTop + 36f, 20f, when {
                nightmare -> VOID
                ascended -> STORM
                else -> Color.WHITE
            })
        if (comboKills >= 5) drawGlyph(c, "🔥 ×$comboKills", W / 2f, safeTop + 58f, 20f, ORANGE)
        if (!ascended && t in 540f..600f)
            drawGlyph(c, "⚡ ASCENSION IN ${ceil(600f - t).toInt()}s", W / 2f, safeTop + 80f, 20f, STORM)
        if (ascended && !nightmare && t in 1140f..1200f)
            drawGlyph(c, "☠ NIGHTMARE IN ${ceil(1200f - t).toInt()}s", W / 2f, safeTop + 80f, 20f, VOID)

        lp.textSize = 16f
        var wx = 14f + safeLeft
        for (w in weaponTpls) {
            val lvl = stacks[w.id] ?: 0
            if (lvl <= 0) continue
            if (evo(w.id)) {
                lp.color = GOLD
                c.drawText("${w.evoIcon}★", wx, safeTop + 68f, lp)
                wx += lp.measureText("${w.evoIcon}★") + 6f
            } else {
                lp.color = Color.WHITE
                c.drawText("${w.icon}$lvl", wx, safeTop + 68f, lp)
                wx += lp.measureText("${w.icon}$lvl") + 6f
            }
        }
        rp.textSize = 16f
        rp.color = if (p.armorPts > 0f) CYAN else Color.argb(120, 200, 205, 215)
        c.drawText("🛡${p.armorPts.roundToInt()} 👟${(p.dodgePct * 100f).roundToInt()}%",
            W - safeRight - 14f, safeTop + 68f, rp)

        boss?.let { b ->
            val filled = ((b.hp / b.maxHp).coerceIn(0f, 1f) * 20f).roundToInt()
            drawGlyph(c, "${b.glyph}${"█".repeat(filled)}${"░".repeat(20 - filled)}${b.glyph}",
                W / 2f, safeTop + 102f, 18f, if (b.vari == 3) STORM else RED)
        }

        val full = (p.hp / p.maxHp * 10f).roundToInt().coerceIn(0, 10)
        val key = full * 10 + p.shield
        if (key != hpKey) {
            hpKey = key
            hpStr = "❤️".repeat(full) + "🖤".repeat(10 - full) +
                (if (p.shield > 0) "  " + "🛡️".repeat(p.shield) else "")
        }
        drawGlyph(c, hpStr, W / 2f, H - safeBottom - 26f, 24f, 0)

        if (t < 6f) {
            val a = (255f * (1f - t / 6f)).toInt().coerceIn(0, 255)
            drawGlyph(c, "drag anywhere to move", W / 2f, H * 0.35f, 22f, Color.argb(a, 255, 255, 255))
        }
        if (joyDown) {
            fill.style = Paint.Style.STROKE; fill.strokeWidth = 3f
            fill.color = Color.argb(60, 255, 255, 255)
            c.drawCircle(joyBaseX, joyBaseY, 52f, fill)
            var dx = joyX - joyBaseX; var dy = joyY - joyBaseY
            val len = hypot(dx, dy)
            if (len > 52f) { dx = dx / len * 52f; dy = dy / len * 52f }
            fill.style = Paint.Style.FILL
            fill.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(joyBaseX + dx, joyBaseY + dy, 22f, fill)
        }
        if (state == State.PLAY) {
            pauseRect.set(12f, safeTop + 16f, 58f, safeTop + 58f)
            drawButton(c, pauseRect, "⏸", CYAN)
        }
    }

    fun drawStarter(c: Canvas) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(195, 6, 8, 18)
        c.drawRect(0f, 0f, W, H, fill)
        drawGlyph(c, "🧙 CHOOSE STARTING WEAPON", W / 2f, H * 0.15f, 30f, GOLD)
        drawGlyph(c, "your mage's base bolt ✧ is always active — pick a line to master",
            W / 2f, H * 0.205f, 13.5f, Color.rgb(190, 195, 210))
        if (dailyMode) drawGlyph(c, "📅 daily seed — same offers & luck for everyone today",
            W / 2f, H * 0.25f, 13f, CYAN)
        cardRects.clear()
        val cw = min(W - 30f, 480f)
        val ch = (H * 0.16f).coerceIn(100f, 128f)
        var y = H * 0.30f
        for (w in starterOffers) {
            val r = RectF(W / 2f - cw / 2f, y, W / 2f + cw / 2f, y + ch)
            cardRects.add(r)
            fill.style = Paint.Style.FILL
            fill.color = Color.argb(240, 20, 24, 42)
            c.drawRoundRect(r, 16f, 16f, fill)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 3f
            fill.color = RARITY_COLOR[1]
            c.drawRoundRect(r, 16f, 16f, fill)
            drawGlyph(c, w.icon, r.left + 44f, r.centerY(), 46f, 0)
            lp.textSize = 23f; lp.color = Color.WHITE
            c.drawText(w.name, r.left + 86f, r.top + ch * 0.38f, lp)
            lp.textSize = 15f; lp.color = Color.rgb(205, 210, 225)
            c.drawText(w.lvlDesc(1), r.left + 86f, r.top + ch * 0.66f, lp)
            lp.textSize = 12f; lp.color = Color.rgb(130, 175, 205)
            c.drawText("MAX + ${w.pairIcon}×${w.pairReq} → ${w.evoIcon} ${w.evoName}",
                r.left + 86f, r.top + ch * 0.89f, lp)
            y += ch + 16f
        }
    }

    fun drawPause(c: Canvas) {
        val p = player
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(200, 6, 8, 18)
        c.drawRect(0f, 0f, W, H, fill)
        drawGlyph(c, "⏸ PAUSED" + (if (dailyMode) "  📅" else ""), W / 2f, H * 0.09f, 38f, GOLD)
        val tag = when { nightmare -> "☠☠"; ascended -> "⚡"; else -> "" }
        drawGlyph(c, "⏱ ${fmtTime(t)}    ☠ $kills    ⬆ Lv ${p.level}  $tag",
            W / 2f, H * 0.15f, 20f, if (nightmare) VOID else if (ascended) STORM else Color.WHITE)
        drawGlyph(c, "✧ grazes $grazes    💢 taken ${dmgTaken.roundToInt()}    📦 $chestsOpened",
            W / 2f, H * 0.195f, 17f, Color.rgb(190, 195, 210))
        drawGlyph(c, "🔥 combo ×$bestCombo    ☄ bosses $bossesDown    🏆 $scoreRun",
            W / 2f, H * 0.24f, 17f, Color.rgb(190, 195, 210))

        val wepStr = weaponTpls.filter { (stacks[it.id] ?: 0) > 0 }.joinToString("  ") { w ->
            if (evo(w.id)) "${w.evoIcon}★" else "${w.icon}${stacks[w.id]}"
        }
        if (wepStr.isNotEmpty()) drawGlyph(c, wepStr, W / 2f, H * 0.31f, 17f, CYAN)
        val passStr = weaponTpls.filter { evo(it.id) }.joinToString(" ") { "★${it.pName}" }
        if (passStr.isNotEmpty()) drawGlyph(c, passStr, W / 2f, H * 0.35f, 14f, GOLD)

        drawGlyph(c, "— equipment —", W / 2f, H * 0.40f, 15f, Color.rgb(150, 155, 175))
        var ey = H * 0.425f
        for (i in 0..4) {
            val pc = equipment[i]
            val line = if (pc == null) "${SLOT_ICONS[i]} ${SLOT_NAMES[i]} —"
            else "${SLOT_ICONS[i]} ${RARITY_GLYPH[pc.rarity]} ${affixSummary(pc)}"
            drawGlyph(c, line, W / 2f, ey, 14f,
                if (pc == null) Color.rgb(95, 100, 115) else RARITY_COLOR[pc.rarity])
            ey += 24f
        }

        resumeRect.set(W / 2f - 120f, H * 0.62f, W / 2f + 120f, H * 0.62f + 54f)
        restartRect.set(W / 2f - 120f, H * 0.62f + 66f, W / 2f + 120f, H * 0.62f + 120f)
        drawButton(c, resumeRect, "▶ RESUME", GREEN)
        drawButton(c, restartRect, "⟳ RESTART", ORANGE)
        sfxRect.set(W * 0.02f, H - safeBottom - 108f, W * 0.25f, H - safeBottom - 68f)
        musRect.set(W * 0.26f, H - safeBottom - 108f, W * 0.49f, H - safeBottom - 68f)
        fxRect.set(W * 0.50f, H - safeBottom - 108f, W * 0.73f, H - safeBottom - 68f)
        perfRect.set(W * 0.74f, H - safeBottom - 108f, W * 0.98f, H - safeBottom - 68f)
        drawButton(c, sfxRect, if (soundOn) "🔊 SFX" else "🔇 SFX", CYAN)
        drawButton(c, musRect, if (musicOn) "🎵 MUSIC" else "🎵 OFF", CYAN)
        drawButton(c, fxRect, if (fxOn) "✨ FX" else "🌙 FX", CYAN)
        drawButton(c, perfRect, if (perfMode) "⚡ PERF" else "🍯 FULL", if (perfMode) GREEN else GOLD)
        drawButton(c, codexRectF(), "📖 CODEX", CYAN)
        drawGlyph(c, "auto-pauses if you switch apps", W / 2f, H - safeBottom - 48f, 14f, Color.rgb(140, 145, 165))
    }

    fun drawButton(c: Canvas, r: RectF, text: String, color: Int) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(235, 22, 27, 46)
        c.drawRoundRect(r, 14f, 14f, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 3f
        fill.color = color
        c.drawRoundRect(r, 14f, 14f, fill)
        drawGlyph(c, text, r.centerX(), r.centerY(), 18f, color)
    }

    fun drawArmorPanel(c: Canvas, r: RectF, pc: ArmorPiece, tag: String) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(240, 18, 22, 38)
        c.drawRoundRect(r, 14f, 14f, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 3f
        fill.color = RARITY_COLOR[pc.rarity]
        c.drawRoundRect(r, 14f, 14f, fill)
        drawGlyph(c, SLOT_ICONS[pc.slot], r.left + 42f, r.centerY(), 44f, 0)
        lp.textSize = 20f; lp.color = RARITY_COLOR[pc.rarity]
        c.drawText("$tag ${RARITY_GLYPH[pc.rarity]}", r.left + 84f, r.top + 30f, lp)
        rp.textSize = 14f; rp.color = Color.rgb(160, 165, 180)
        c.drawText("score ${pc.score.roundToInt()}", r.right - 14f, r.top + 30f, rp)
        var ay = r.top + 62f
        lp.textSize = 15.5f; lp.color = Color.WHITE
        for (a in AFFIX_TPLS) {
            val v = pc.affixes[a.id] ?: continue
            c.drawText("${a.icon} +${fmtVal(v, a.isPct)} ${a.label}", r.left + 84f, ay, lp)
            ay += 25f
        }
    }

    fun drawArmor(c: Canvas) {
        drawHud(c)
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(185, 5, 7, 16)
        c.drawRect(0f, 0f, W, H, fill)
        val pa = pendingArmor ?: return

        drawGlyph(c, "📦 ARMOR FOUND", W / 2f, H * 0.10f, 34f, GOLD)
        drawGlyph(c, "${SLOT_NAMES[pa.slot]} — ${RARITY_PREFIX[pa.rarity]}${RARITY_GLYPH[pa.rarity]}"
            .replace("  ", " ").trim(), W / 2f, H * 0.155f, 18f, RARITY_COLOR[pa.rarity])

        val pw = min(W - 36f, 480f)
        val ph = H * 0.21f
        newRect.set(W / 2f - pw / 2f, H * 0.20f, W / 2f + pw / 2f, H * 0.20f + ph)
        oldRect.set(W / 2f - pw / 2f, H * 0.435f, W / 2f + pw / 2f, H * 0.435f + ph)
        drawArmorPanel(c, newRect, pa, "NEW")
        val old = pendingOld
        if (old != null) drawArmorPanel(c, oldRect, old, "CURRENT")
        else {
            fill.style = Paint.Style.FILL
            fill.color = Color.argb(240, 14, 16, 28)
            c.drawRoundRect(oldRect, 14f, 14f, fill)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 2f
            fill.color = Color.argb(120, 120, 125, 140)
            c.drawRoundRect(oldRect, 14f, 14f, fill)
            drawGlyph(c, "— empty ${SLOT_NAMES[pa.slot]} slot —", oldRect.centerX(), oldRect.centerY(), 19f,
                Color.rgb(120, 125, 140))
        }

        val better = old == null || pa.score >= old.score
        drawGlyph(c, if (better) "▲ recommended" else "▲ equipped is better", W / 2f, H * 0.665f, 15f,
            if (better) GREEN else ORANGE)

        equipRect.set(W * 0.06f, H * 0.70f, W * 0.485f, H * 0.78f)
        salvageRect.set(W * 0.515f, H * 0.70f, W * 0.94f, H * 0.78f)
        drawButton(c, equipRect, "✅ EQUIP", GREEN)
        drawButton(c, salvageRect, "♻️ SALVAGE +${(25f + pa.rarity * 15f).roundToInt()}❤️", ORANGE)
        drawGlyph(c, "tap a panel or a button — game is paused", W / 2f, H * 0.83f, 14f, Color.rgb(140, 145, 165))
    }

    fun drawLevelUp(c: Canvas) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(170, 6, 8, 18)
        c.drawRect(0f, 0f, W, H, fill)
        drawGlyph(c, "⬆ LEVEL ${player.level}", W / 2f, safeTop + H * 0.12f, 40f, GOLD)
        var hint = "choose a blessing"
        if (rerolls > 0) hint += "   🎲 ×$rerolls"
        if (banishes > 0) hint += "   🚫 ×$banishes"
        drawGlyph(c, hint, W / 2f, safeTop + H * 0.17f, 20f, Color.rgb(190, 195, 210))
        if (rerolls > 0) {
            rerollRect.set(W - 170f, safeTop + H * 0.045f, W - 16f, safeTop + H * 0.045f + 46f)
            drawButton(c, rerollRect, "🎲 REROLL ×$rerolls", CYAN)
        }

        cardRects.clear()
        banRects.clear()
        val cw = min(W - 30f, 480f)
        val ch = (H * 0.17f).coerceIn(102f, 132f)
        var y = safeTop + H * 0.22f
        for (u in upgrades) {
            val r = RectF(W / 2f - cw / 2f, y, W / 2f + cw / 2f, y + ch)
            cardRects.add(r)
            fill.style = Paint.Style.FILL
            fill.color = Color.argb(240, 20, 24, 42)
            c.drawRoundRect(r, 16f, 16f, fill)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = if (u.kind == 2) 5f else 3f
            fill.color = if (u.kind == 2) GOLD else RARITY_COLOR[u.rarity]
            c.drawRoundRect(r, 16f, 16f, fill)
            if (u.kind == 2) drawGlyph(c, "★ EVOLUTION ★", r.centerX(), r.top + 15f, 13f, GOLD)
            drawGlyph(c, u.icon, r.left + 44f, r.centerY() + (if (u.kind == 2) 6f else 0f),
                if (u.kind == 2) 50f else 44f, 0)
            lp.textSize = if (u.kind == 2) 26f else 23f
            lp.color = if (u.kind == 2) GOLD else RARITY_COLOR[u.rarity]
            c.drawText(u.title, r.left + 86f, r.top + ch * 0.38f, lp)
            lp.textSize = 16f; lp.color = Color.rgb(205, 210, 225)
            c.drawText(u.desc, r.left + 86f, r.top + ch * 0.66f, lp)
            if (u.sub.isNotEmpty()) {
                lp.textSize = 12.5f
                lp.color = if (u.kind == 2) GOLD else Color.rgb(130, 175, 205)
                c.drawText(u.sub, r.left + 86f, r.top + ch * 0.89f, lp)
            }
            drawGlyph(c, if (u.kind == 2) "★" else RARITY_GLYPH[u.rarity],
                r.right - 22f, r.top + 22f, 18f, if (u.kind == 2) GOLD else RARITY_COLOR[u.rarity])
            val owned = stacks[u.id] ?: 0
            if (u.kind != 2 && owned > 0)
                drawGlyph(c, "×$owned", r.right - 28f, r.bottom - 18f, 16f, Color.rgb(150, 155, 170))
            if (u.kind in 0..1 && u.id != "feast" && banishes > 0) {
                val br = RectF(r.right - 66f, r.top + 40f, r.right - 8f, r.top + 72f)
                banRects.add(br)
                fill.style = Paint.Style.FILL
                fill.color = Color.argb(220, 40, 22, 28)
                c.drawRoundRect(br, 10f, 10f, fill)
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = 2f
                fill.color = Color.rgb(235, 100, 100)
                c.drawRoundRect(br, 10f, 10f, fill)
                drawGlyph(c, "🚫", br.centerX(), br.centerY(), 17f, 0)
            } else banRects.add(RectF())
            y += ch + 16f
        }
        val bs = weaponTpls.filter { (stacks[it.id] ?: 0) > 0 }.joinToString(" ") { w ->
            if (evo(w.id)) "${w.evoIcon}★" else "${w.icon}${stacks[w.id]}"
        }
        if (bs.isNotEmpty())
            drawGlyph(c, bs, W / 2f, H * 0.955f, 13f, Color.rgb(150, 160, 180))
    }

    fun drawCodex(c: Canvas) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(225, 6, 8, 18)
        c.drawRect(0f, 0f, W, H, fill)
        drawGlyph(c, "📖 CODEX  ${discovered.size}/${weaponTpls.size}", W / 2f, H * 0.06f, 34f, GOLD)
        drawGlyph(c, "${weaponSlots()} slots — 🎯 draft a starter — 🚫 banish — ⚡10:00 ascend — ☠20:00 nightmare",
            W / 2f, H * 0.10f, 12.5f, Color.rgb(170, 175, 195))
        var y = H * 0.14f
        for (w in weaponTpls) {
            lp.textSize = 14.5f; lp.color = Color.WHITE
            c.drawText("${w.icon} ${w.name}   (max ${w.maxLvl} + ${w.pairIcon}×${w.pairReq})", 18f, y, lp)
            if (w.id in discovered) {
                lp.textSize = 12f; lp.color = GOLD
                c.drawText("${w.evoIcon} ${w.evoName} — ${w.evoDesc}", 42f, y + 19f, lp)
                lp.textSize = 11.5f; lp.color = CYAN
                c.drawText("★ ${w.pName}: ${w.pDesc}", 42f, y + 37f, lp)
            } else {
                lp.textSize = 12f; lp.color = Color.rgb(120, 125, 140)
                c.drawText("🔒 ??? — evolve in a run to reveal", 42f, y + 19f, lp)
            }
            y += 62f
        }
        drawGlyph(c, "tap anywhere to return ◀", W / 2f, H * 0.955f, 15f,
            Color.argb((160f + 90f * (0.5f + 0.5f * sin(animT * 4f))).toInt().coerceIn(0, 255), 140, 200, 240))
    }

    fun drawMenu(c: Canvas) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(120, 5, 6, 16)
        c.drawRect(0f, 0f, W, H, fill)
        val pulse = (0.75f + 0.25f * sin(menuT * 3f)).coerceIn(0f, 1f)
        metaRect.set(12f + safeLeft, safeTop + 8f, 156f + safeLeft, safeTop + 48f)
        diffRect.set(12f + safeLeft, safeTop + 56f, 156f + safeLeft, safeTop + 96f)
        achvRect.set(12f + safeLeft, safeTop + 104f, 156f + safeLeft, safeTop + 144f)
        dailyRect.set(12f + safeLeft, safeTop + 152f, 156f + safeLeft, safeTop + 192f)
        drawGlyph(c, "✦ ⚔️ ✦", W / 2f, H * 0.185f, 34f, Color.rgb(180, 130, 255))
        drawGlyph(c, "GLYPHSTORM", W / 2f, H * 0.27f, 52f, Color.WHITE)
        drawGlyph(c, "🧙 vs 👾🦇💀🦠🦂👹🕷️🐲🌩️🕳️", W / 2f, H * 0.345f, 30f, 0)
        drawGlyph(c, "◈ draft a starter 🎯 → max it + pair stat → EVOLUTION ★", W / 2f, H * 0.425f, 14f, GOLD)
        drawGlyph(c, "◈ ⚡10:00 ASCEND (×1.5) ◈ ☠20:00 NIGHTMARE (×1.25)", W / 2f, H * 0.468f, 14f, VOID)
        drawGlyph(c, "◈ 📦 armor ◈ 🚫 banish ◈ 🎲 reroll ◈ graze ✧ for XP", W / 2f, H * 0.511f, 14f, Color.rgb(140, 145, 165))
        drawGlyph(c, "✦ $shards shards — spent on ⬆ META", W / 2f, H * 0.575f, 16f, CYAN)
        if (dailyBest() > 0)
            drawGlyph(c, "📅 today's daily best: ${dailyBest()}", W / 2f, H * 0.615f, 15f, GOLD)
        drawGlyph(c, "TAP TO START ▶      📅 for the daily run",
            W / 2f, H * 0.665f, 24f, Color.argb((pulse * 255f).toInt(), 120, 220, 255))
        if (best > 0f || bestScore > 0)
            drawGlyph(c, "🏆 best score $bestScore   ⏱ best time ${fmtTime(best)}   ☠☠ $totalKills",
                W / 2f, H * 0.745f, 15f, Color.rgb(150, 155, 175))
        if (hasSnapshot()) {
            contRect.set(W / 2f - 130f, H * 0.79f, W / 2f + 130f, H * 0.79f + 50f)
            drawButton(c, contRect, "⏸ CONTINUE  ⏱ ${fmtTime(prefs.getFloat("s_t", 0f))}", GOLD)
        }
        drawButton(c, codexRectF(), "📖 CODEX", CYAN)
        drawButton(c, metaRect, "⬆ META ✦$shards", GOLD)
        drawButton(c, diffRect, diffName, if (diff == 2) RED else if (diff == 0) ICE else GOLD)
        drawButton(c, achvRect, "🏆 ${achvUnlocked.size}/${achvTpls.size}", GOLD)
        drawButton(c, dailyRect, "📅 DAILY", CYAN)
        sfxRect.set(W * 0.02f, H - safeBottom - 52f, W * 0.25f, H - safeBottom - 14f)
        musRect.set(W * 0.26f, H - safeBottom - 52f, W * 0.49f, H - safeBottom - 14f)
        fxRect.set(W * 0.50f, H - safeBottom - 52f, W * 0.73f, H - safeBottom - 14f)
        perfRect.set(W * 0.74f, H - safeBottom - 52f, W * 0.98f, H - safeBottom - 14f)
        drawButton(c, sfxRect, if (soundOn) "🔊 SFX" else "🔇 SFX", CYAN)
        drawButton(c, musRect, if (musicOn) "🎵 MUSIC" else "🎵 OFF", CYAN)
        drawButton(c, fxRect, if (fxOn) "✨ FX" else "🌙 FX", CYAN)
        drawButton(c, perfRect, if (perfMode) "⚡ PERF" else "🍯 FULL", if (perfMode) GREEN else GOLD)
    }

    fun drawMeta(c: Canvas) {
        buyRects.clear()
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(215, 5, 7, 16)
        c.drawRect(0f, 0f, W, H, fill)
        drawGlyph(c, "⬆ META UPGRADES", W / 2f, H * 0.075f, 32f, GOLD)
        drawGlyph(c, "✦ $shards available", W / 2f, H * 0.12f, 20f, CYAN)
        val rh = max(60f, H * 0.095f)
        var y = H * 0.155f
        for (pk in META_PERKS) {
            val lv = metaLv[pk.id] ?: 0
            val row = RectF(W * 0.05f, y, W * 0.95f, y + rh)
            fill.style = Paint.Style.FILL
            fill.color = Color.argb(240, 18, 22, 38)
            c.drawRoundRect(row, 14f, 14f, fill)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 2.5f
            fill.color = if (lv >= pk.maxLvl) GOLD else Color.argb(120, 90, 110, 150)
            c.drawRoundRect(row, 14f, 14f, fill)
            drawGlyph(c, pk.icon, row.left + 34f, row.centerY(), 32f, 0)
            lp.textSize = 18f; lp.color = Color.WHITE
            c.drawText("${pk.name}  Lv$lv/${pk.maxLvl}", row.left + 68f, row.top + rh * 0.38f, lp)
            lp.textSize = 12f; lp.color = Color.rgb(175, 180, 195)
            c.drawText(pk.desc, row.left + 68f, row.top + rh * 0.74f, lp)
            val buy = RectF(row.right - 106f, row.centerY() - 20f, row.right - 12f, row.centerY() + 20f)
            buyRects.add(buy)
            if (lv >= pk.maxLvl) drawButton(c, buy, "MAX", GOLD)
            else {
                val cost = metaCost(lv)
                drawButton(c, buy, "✦$cost", if (shards >= cost) GREEN else Color.rgb(120, 125, 140))
            }
            y += rh + 6f
        }
        metaCloseRect.set(W - 64f, 16f, W - 16f, 56f)
        drawButton(c, metaCloseRect, "✕", CYAN)
        drawGlyph(c, "earn ✦ shards: ☠ kills ÷ 8 + ⏱ 6 per minute (×difficulty ×ascension)", W / 2f, H * 0.965f, 13f,
            Color.rgb(140, 145, 165))
    }

    fun drawAchv(c: Canvas) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(215, 5, 7, 16)
        c.drawRect(0f, 0f, W, H, fill)
        drawGlyph(c, "🏆 ACHIEVEMENTS  ${achvUnlocked.size}/${achvTpls.size}", W / 2f, H * 0.07f, 30f, GOLD)
        val rh = max(58f, H * 0.072f)
        var y = H * 0.12f
        for (a in achvTpls) {
            val got = a.id in achvUnlocked
            val row = RectF(W * 0.05f, y, W * 0.95f, y + rh)
            fill.style = Paint.Style.FILL
            fill.color = Color.argb(240, 18, 22, 38)
            c.drawRoundRect(row, 12f, 12f, fill)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 2.5f
            fill.color = if (got) GOLD else Color.argb(110, 80, 85, 105)
            c.drawRoundRect(row, 12f, 12f, fill)
            drawGlyph(c, if (got) a.icon else "🔒", row.left + 30f, row.centerY(), 27f, 0)
            lp.textSize = 16f; lp.color = if (got) Color.WHITE else Color.rgb(120, 125, 140)
            c.drawText(a.name, row.left + 62f, row.top + rh * 0.40f, lp)
            lp.textSize = 12f
            lp.color = if (got) Color.rgb(175, 180, 195) else Color.rgb(95, 100, 115)
            c.drawText(a.desc, row.left + 62f, row.top + rh * 0.76f, lp)
            if (got) drawGlyph(c, "✔", row.right - 26f, row.centerY(), 20f, GREEN)
            y += rh + 7f
        }
        achvCloseRect.set(W - 64f, 16f, W - 16f, 56f)
        drawButton(c, achvCloseRect, "✕", CYAN)
    }

    fun drawOver(c: Canvas) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(150, 10, 4, 10)
        c.drawRect(0f, 0f, W, H, fill)

        if (reviveOffer) {
            drawGlyph(c, "💀 DOWNED 💀", W / 2f, H * 0.22f, 44f, RED)
            drawGlyph(c, "survived ${fmtTime(t)} — ☠ $kills kills", W / 2f, H * 0.30f, 22f, Color.WHITE)
            drawGlyph(c, "watch a short video to revive at 60% HP?", W / 2f, H * 0.36f, 16f,
                Color.rgb(190, 195, 210))
            reviveRect.set(W / 2f - 150f, H * 0.42f, W / 2f + 150f, H * 0.42f + 56f)
            giveRect.set(W / 2f - 150f, H * 0.42f + 68f, W / 2f + 150f, H * 0.42f + 124f)
            drawButton(c, reviveRect, "▶️ REVIVE  (video)", GOLD)
            drawButton(c, giveRect, "🏳️ GIVE UP", Color.rgb(150, 155, 170))
            drawGlyph(c, "offered once per run — not in 📅 dailies", W / 2f, H * 0.62f, 13f,
                Color.rgb(140, 145, 165))
            return
        }

        val title = when {
            nightmare -> "☠☠ CONSUMED ☠☠"
            ascended -> "⚡ FELL ASCENDED ⚡"
            else -> "💀 YOU DIED 💀"
        }
        val tCol = when { nightmare -> VOID; ascended -> STORM; else -> RED }
        drawGlyph(c, title, W / 2f, H * 0.19f, if (ascended || nightmare) 36f else 44f, tCol)
        drawGlyph(c, "survived ${fmtTime(t)} — ${diffName.drop(2)}${if (dailyMode) "  📅 DAILY" else ""}",
            W / 2f, H * 0.27f, 24f, Color.WHITE)
        drawGlyph(c, "☠ $kills kills   ⬆ level ${player.level}   🔥 ×$bestCombo   ☄ $bossesDown",
            W / 2f, H * 0.325f, 18f, Color.rgb(200, 205, 220))
        drawGlyph(c, "✧ $grazes grazes   💢 ${dmgTaken.roundToInt()} taken   📦 $chestsOpened",
            W / 2f, H * 0.37f, 15f, Color.rgb(190, 195, 210))
        drawGlyph(c, "🏆 score $scoreRun  (best $bestScore)", W / 2f, H * 0.415f, 19f, GOLD)
        if (dailyMode)
            drawGlyph(c, "📅 daily best today: ${dailyBest()}", W / 2f, H * 0.452f, 16f, CYAN)
        else
            drawGlyph(c, "✦ +$shardsRun shards earned   (bank $shards)", W / 2f, H * 0.455f, 17f, CYAN)

        if (doubleOffer) {
            dblRect.set(W / 2f - 150f, H * 0.49f, W / 2f + 150f, H * 0.49f + 50f)
            drawButton(c, dblRect, "✦ ×2 SHARDS  (video)", GOLD)
        }

        val wepStr = weaponTpls.filter { (stacks[it.id] ?: 0) > 0 }.joinToString("  ") { w ->
            if (evo(w.id)) "${w.evoIcon}★" else "${w.icon}${stacks[w.id]}"
        }
        if (wepStr.isNotEmpty()) drawGlyph(c, wepStr, W / 2f, H * 0.575f, 17f, CYAN)
        val passStr = weaponTpls.filter { evo(it.id) }.joinToString(" ") { "★${it.pName}" }
        if (passStr.isNotEmpty()) drawGlyph(c, passStr, W / 2f, H * 0.61f, 13f, GOLD)

        drawGlyph(c, "— equipment —", W / 2f, H * 0.645f, 15f, Color.rgb(150, 155, 175))
        var ey = H * 0.675f
        for (i in 0..4) {
            val pc = equipment[i]
            val line = if (pc == null) "${SLOT_ICONS[i]} ${SLOT_NAMES[i]} —"
            else "${SLOT_ICONS[i]} ${RARITY_GLYPH[pc.rarity]} ${affixSummary(pc)}"
            drawGlyph(c, line, W / 2f, ey, 14f,
                if (pc == null) Color.rgb(95, 100, 115) else RARITY_COLOR[pc.rarity])
            ey += 24f
        }
        if (overTimer > 0.7f && !doubleOffer) {
            val pulse = (0.7f + 0.3f * sin(animT * 4f)).coerceIn(0f, 1f)
            drawGlyph(c, "tap to try again ⟳", W / 2f, H * 0.88f, 24f,
                Color.argb((pulse * 255f).toInt(), 120, 220, 255))
        }
        drawButton(c, codexRectF(), "📖 CODEX", CYAN)
    }

    // ═══════════ helpers ═══════════

    fun fmtTime(f: Float): String {
        val s = f.toInt()
        if (s != timeSec) {
            timeSec = s
            timeStr = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
        }
        return timeStr
    }

    fun ftext(x: Float, y: Float, s: String, col: Int, size: Float) {
        if (texts.size > 40) texts.removeAt(0)
        texts.add(FloatText(x, y, s, col, size))
    }

    fun addParticle(x: Float, y: Float, vx: Float, vy: Float, life: Float,
                    glyph: String, size: Float, color: Int) {
        if (parts.size > if (perfMode) 240 else 520) return
        parts.add(Particle(x, y, vx, vy, life, glyph, size, color))
    }

    private val fmCache = HashMap<Float, Paint.FontMetrics>()
    fun drawGlyph(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        tp.textSize = size
        tp.color = color
        val fm = fmCache.getOrPut(size) { Paint.FontMetrics().also { tp.getFontMetrics(it) } }
        c.drawText(s, x, y - (fm.ascent + fm.descent) * 0.5f, tp)
    }

    fun rnd(a: Float, b: Float) = a + rng.nextFloat() * (b - a)
}
