package com.vera.timebricks

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// UI roles are neutral surfaces plus one accent; category/event colors stay user-owned.
private data class BricksColors(
    val background: Color, val track: Color, val navigation: Color, val selected: Color,
    val text: Color, val secondary: Color, val chip: Color, val outline: Color,
    val dateSelected: Color, val todayBorder: Color
)
private val DarkBricksColors = BricksColors(
    Color(0xFF070707), Color(0xFF1A1A1A), Color(0xFF141414), Color(0xFF354852),
    Color(0xFFF2F2F2), Color(0xFFA6A6A6), Color(0xFF181818), Color(0xFF454545),
    Color(0xFF354852), Color(0xFF90B9CD)
)
private val LightBricksColors = BricksColors(
    Color(0xFFF5F6F7), Color(0xFFE7EAED), Color(0xFFFFFFFF), Color(0xFFDBE8EF),
    Color(0xFF20272D), Color(0xFF525F69), Color(0xFFEBEEF0), Color(0xFF7D8992),
    Color(0xFFDBE8EF), Color(0xFF527F93)
)
private val LocalBricksColors = staticCompositionLocalOf { DarkBricksColors }
private val LocalAppearance = staticCompositionLocalOf { DisplaySettings() }
private object BricksDimensions {
    val small: Dp @Composable get() = (LocalAppearance.current.cornerDp * .5f).dp
    val medium: Dp @Composable get() = (LocalAppearance.current.cornerDp * .75f).dp
    val pill: Dp @Composable get() = LocalAppearance.current.cornerDp.dp
    val pageInset: Dp @Composable get() = (12 * LocalAppearance.current.spacingScale).dp
    val gap: Dp @Composable get() = (6 * LocalAppearance.current.spacingScale).dp
    val listRow: Dp @Composable get() = (56 * LocalAppearance.current.spacingScale).dp
}
private val Bg: Color @Composable get() = LocalBricksColors.current.background
private val TrackBg: Color @Composable get() = LocalBricksColors.current.track
private val NavBg: Color @Composable get() = LocalBricksColors.current.navigation
private val Accent: Color @Composable get() = LocalBricksColors.current.selected
private val Primary: Color @Composable get() = LocalBricksColors.current.text
private val Secondary: Color @Composable get() = LocalBricksColors.current.secondary

private fun categoryInk(color: Color): Color =
    if (color.luminance() > .179f) Color.Black else Color.White

private fun contrastRatio(a: Color, b: Color): Float {
    val x = a.luminance(); val y = b.luminance()
    return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
}

// Preserve the chosen hue, adjusting tone only when needed for readable controls.
private fun readableAccent(seed: Color, surface: Color, dark: Boolean): Color {
    val target = if (dark) Color.White else Color.Black
    for (step in 0..100) {
        val candidate = lerp(seed, target, step / 100f)
        if (contrastRatio(candidate, surface) >= 4.5f) return candidate
    }
    return target
}

private fun bricksColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val p = if (dark) DarkBricksColors else LightBricksColors
    val primary = readableAccent(seed, p.track, dark)
    val container = lerp(p.background, primary, if (dark) .22f else .16f)
    val fixed = lerp(Color.White, seed, .22f)
    val fixedDim = lerp(Color.White, seed, .38f)
    val onFixedVariant = readableAccent(Color(0xFF303940), fixedDim, false)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = categoryInk(primary),
        primaryContainer = container, onPrimaryContainer = p.text,
        secondary = primary, onSecondary = categoryInk(primary),
        secondaryContainer = container, onSecondaryContainer = p.text,
        tertiary = primary, onTertiary = categoryInk(primary),
        tertiaryContainer = container, onTertiaryContainer = p.text,
        background = p.background, onBackground = p.text,
        surface = p.navigation, onSurface = p.text,
        surfaceVariant = p.track, onSurfaceVariant = p.secondary,
        surfaceTint = primary,
        inverseSurface = if (dark) LightBricksColors.track else DarkBricksColors.track,
        inverseOnSurface = if (dark) LightBricksColors.text else DarkBricksColors.text,
        inversePrimary = readableAccent(seed, if (dark) LightBricksColors.track else DarkBricksColors.track, !dark),
        outline = p.outline, outlineVariant = lerp(p.background, p.outline, .4f), scrim = Color.Black,
        error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
        onError = if (dark) Color(0xFF690005) else Color.White,
        errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6),
        onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002),
        surfaceDim = p.background, surfaceBright = if (dark) p.track else Color.White,
        surfaceContainerLowest = p.background, surfaceContainerLow = p.navigation,
        surfaceContainer = p.chip, surfaceContainerHigh = p.track,
        surfaceContainerHighest = lerp(p.track, p.text, .05f),
        primaryFixed = fixed, primaryFixedDim = fixedDim,
        onPrimaryFixed = Color(0xFF171A1D), onPrimaryFixedVariant = onFixedVariant,
        secondaryFixed = fixed, secondaryFixedDim = fixedDim,
        onSecondaryFixed = Color(0xFF171A1D), onSecondaryFixedVariant = onFixedVariant,
        tertiaryFixed = fixed, tertiaryFixedDim = fixedDim,
        onTertiaryFixed = Color(0xFF171A1D), onTertiaryFixedVariant = onFixedVariant
    )
}

@Composable
private fun TimeBricksTheme(settings: DisplaySettings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = settings.usesDarkTheme(systemDark)
    val scheme = remember(settings.themeHex, dark) { bricksColorScheme(Color(parseThemeHex(settings.themeHex)!!), dark) }
    val palette = (if (dark) DarkBricksColors else LightBricksColors).copy(
        selected = scheme.primaryContainer, dateSelected = scheme.primaryContainer, todayBorder = scheme.primary)
    val systemDensity = LocalDensity.current
    val density = remember(systemDensity, settings.fontScale) {
        Density(systemDensity.density, systemDensity.fontScale * settings.fontScale)
    }
    CompositionLocalProvider(LocalBricksColors provides palette, LocalAppearance provides settings,
        LocalDensity provides density) {
        MaterialTheme(colorScheme = scheme, shapes = Shapes(
            extraSmall = RoundedCornerShape(BricksDimensions.small / 2),
            small = RoundedCornerShape(BricksDimensions.small),
            medium = RoundedCornerShape(BricksDimensions.medium),
            large = RoundedCornerShape(BricksDimensions.pill),
            extraLarge = RoundedCornerShape(BricksDimensions.pill * 1.5f)
        ), content = content)
    }
}


// Stable IDs, including child IDs, are the only event/category relationship.
private data class Subcategory(
    val id: String, val name: String, val color: Color, val archived: Boolean = false
)
private data class Category(
    val id: String, val name: String, val color: Color,
    val children: List<Subcategory>, val archived: Boolean = false
)

private fun seedCategory(id: String, name: String, color: Color, names: List<String>) =
    Category(id, name, color, names.map { Subcategory("$id:$it", it, color) })

private val initialCategories = listOf(
    seedCategory("work", "工作", Color(0xFF4D706B), listOf("专注", "会议", "沟通")),
    seedCategory("study", "学习", Color(0xFF81749D), listOf("阅读", "课程", "练习")),
    seedCategory("life", "生活", Color(0xFFA08B6B), listOf("用餐", "通勤", "家务")),
    seedCategory("sport", "运动", Color(0xFF758B72), listOf("散步", "跑步", "训练")),
    seedCategory("rest", "休息", Color(0xFF627F92), listOf("睡眠", "午休", "放空")),
    seedCategory("play", "娱乐", Color(0xFF956D82), listOf("电影", "游戏", "社交"))
)

private data class DemoEvent(
    val start: Int, val end: Int, val categoryId: String,
    val child: String = "", // Stable child ID; empty means parent category only.
    val id: String = UUID.randomUUID().toString(),
    val scheduleIdentity: String? = null, // Retained when inherited rows become dated snapshots.
    val dayOffset: Int = 0, // Actual start date relative to immutable identity/occurrence date.
    val importedTemplateId: String? = null,
    val originDay: Long? = null // View-only provenance; never serialized or copied into storage.
)

private fun minuteAt(position: Offset, matrix: IntSize, density: Float): Int? {
    val labelWidth = 27f * density
    val rowHeight = (matrix.height - 7f * 5f * density) / 8f
    val rowStep = rowHeight + 5f * density
    if (matrix.width <= labelWidth || position.x < labelWidth || position.x > matrix.width) return null
    if (position.y < 0f || position.y >= matrix.height) return null
    val row = (position.y / rowStep).toInt().coerceIn(0, 7)
    if (position.y - row * rowStep >= rowHeight) return null
    val fraction = ((position.x - labelWidth) / (matrix.width - labelWidth)).coerceIn(0f, 1f)
    return row * 180 + ((fraction * 180f / 5f).toInt() * 5).coerceAtMost(175)
}

private val demoEvents = listOf(
    DemoEvent(0, 390, "rest", "rest:睡眠"),
    DemoEvent(420, 480, "life", "life:用餐"),
    DemoEvent(540, 705, "study", "study:课程"),
    DemoEvent(780, 840, "rest", "rest:午休"),
    DemoEvent(900, 1080, "study", "study:练习"),
    DemoEvent(1110, 1170, "sport"),
    DemoEvent(1260, 1380, "play")
).mapIndexed { index, event -> event.copy(id = "demo-$index") }

class MainActivity : ComponentActivity() {
    private var widgetRequest by mutableStateOf<WidgetOpenRequest?>(null)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetRequest = ScheduleWidgets.openRequest(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        widgetRequest?.let {
            outState.putLong("pendingWidgetDay", it.day)
            outState.putString("pendingWidgetEvent", it.eventId)
            outState.putString("pendingWidgetDestination", it.destination)
        }
        super.onSaveInstanceState(outState)
    }
    private val store by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TimeBricksStore(application) as T
        })[TimeBricksStore::class.java]
    }
    override fun onResume() {
        super.onResume()
        AutoBackupSystem.initialize(application)
        AutoBackupSystem.checkNow(application)
        ReminderSystem.refreshAsync(application)
        ScheduleWidgets.checkDay(application)
        StatusScheduleOverlay.ensureRunning(application)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) widgetRequest = ScheduleWidgets.openRequest(intent)
        else if (savedInstanceState.containsKey("pendingWidgetDay")) {
            val day = savedInstanceState.getLong("pendingWidgetDay")
            if (WidgetPolicy.validDay(day)) widgetRequest = WidgetOpenRequest(day, savedInstanceState.getString("pendingWidgetEvent"), savedInstanceState.getString("pendingWidgetDestination") ?: "planning")
        }
        setContent {
            TimeBricksTheme(store.data.settings) {

                key(store.restoreGeneration) {
                    BackupControls(store) { export, import ->
                        ScheduleExportControls(store) { scheduleExport -> TimeBricksApp(store, export, import, scheduleExport, widgetRequest) { widgetRequest = null } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeBricksApp(store: TimeBricksStore, onExport: () -> Unit, onImport: () -> Unit, onScheduleExport: (Long) -> Unit, widgetRequest: WidgetOpenRequest?, onWidgetConsumed: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(0) }
    var widgetCreate by rememberSaveable { mutableStateOf(false) }
    var epochDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var mainId by rememberSaveable { mutableStateOf("work") }
    var childId by rememberSaveable { mutableStateOf("") }
    // "main" manages parents; any category ID opens that parent's children.
    var editTarget by rememberSaveable { mutableStateOf("") }
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    // Preserve the existing activity-recreation behavior without clearing card jumps.
    LaunchedEffect(Unit) { if (widgetRequest == null) selectedEventId = null }
    var scheduleMode by rememberSaveable { mutableStateOf("schedule") }
    var managingTemplates by rememberSaveable { mutableStateOf(false) }
    var importingTemplate by remember { mutableStateOf<Pair<Long, AppData>?>(null) }
    var clearRequest by remember { mutableStateOf<TemplateRequest?>(null) }
    val openTemplates: () -> Unit = { managingTemplates = true }
    var managingRepeats by rememberSaveable { mutableStateOf(false) }
    var repeatEdit by remember { mutableStateOf<RepeatEditRequest?>(null) }
    var repeatScope by remember { mutableStateOf<RepeatScopeRequest?>(null) }
    fun editOccurrence(day: Long, id: String, replacement: DemoEvent?) {
        val snapshot = store.data
        val anchor = snapshot.eventsOn(day).firstOrNull { it.id == id }?.originDay ?: day
        if (snapshot.ruleForOccurrence(anchor, id) != null) {
            repeatScope = RepeatScopeRequest(anchor, id, replacement?.storedFromView(day, anchor), snapshot)
        } else store.change(if (replacement == null) "事件已删除" else "事件已更新", {
            if (replacement == null) it.removeEvent(day, id) else it.modifyEvent(day, id) { replacement }
        }, trackUndo = true)
    }
    val scheduleState = rememberSaveableStateHolder()
    // Clear ordinary navigation synchronously; a card jump sets date + ID together.
    // A page-keyed effect would erase that ID after PlanningPage has appeared.
    val changeDate: (LocalDate) -> Unit = {
        if (epochDay != it.toEpochDay()) selectedEventId = null
        epochDay = it.toEpochDay()
    }
    if (!store.loaded) {
        Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (store.loadError.isEmpty()) "正在读取本地数据…" else store.loadError, color = Primary)
            if (store.loadError.isNotEmpty()) TextButton(onClick = { store.reload() }, enabled = !store.busy) { Text("重试") }
        }
        return
    }
    val data = store.data
    val dayEvents = data.eventsOn(epochDay)
    LaunchedEffect(epochDay, dayEvents) {
        if (selectedEventId != null && store.data.eventsOn(epochDay).none { it.id == selectedEventId }) selectedEventId = null
    }
    LaunchedEffect(widgetRequest) {
        widgetRequest?.let { request ->
            epochDay = request.day
            selectedEventId = request.eventId?.takeIf { id -> store.data.eventsOn(request.day).any { it.id == id } }
            page = if (request.destination == "schedule") 1 else 0
            scheduleMode = "schedule"
            widgetCreate = request.destination == "create"
            managingTemplates = false; managingRepeats = false; editTarget = ""
            repeatEdit = null; repeatScope = null; clearRequest = null
            onWidgetConsumed()
        }
    }
    val activeCategory = data.categories.firstOrNull { it.id == mainId && !it.archived }
        ?: data.categories.firstOrNull { !it.archived }
    val child = childId.takeIf { id -> activeCategory?.children?.any { it.id == id && !it.archived } == true } ?: ""
    LaunchedEffect(activeCategory?.id, child) {
        mainId = activeCategory?.id ?: ""
        childId = child
    }
    Column(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                0 -> PlanningPage(
                    externalCreate = widgetCreate, onExternalCreateConsumed = { widgetCreate = false },
                    date = LocalDate.ofEpochDay(epochDay), onDateChange = changeDate,
                    categories = data.categories, activeCategory = activeCategory, child = child,
                    onMainSelect = { newId ->
                        if (selectedEventId != null) {
                            val day = epochDay; val id = selectedEventId!!
                            store.data.eventsOn(day).firstOrNull { it.id == id }?.let { editOccurrence(day, id, it.copy(categoryId = newId, child = "")) }
                        }
                        mainId = newId; childId = ""
                    },
                    onChildSelect = { newChild ->
                        if (selectedEventId != null && activeCategory != null) {
                            val day = epochDay; val id = selectedEventId!!
                            store.data.eventsOn(day).firstOrNull { it.id == id }?.let { editOccurrence(day, id, it.copy(categoryId = activeCategory.id, child = newChild)) }
                        }
                        childId = newChild
                    },
                    onEditMain = { editTarget = "main" },
                    onEditChildren = { activeCategory?.let { editTarget = it.id } },
                    events = dayEvents,
                    selectedEventId = selectedEventId, onSelectEvent = { selectedEventId = it },
                    onDeleteEvent = { id ->
                        editOccurrence(epochDay, id, null)
                    },
                    onEditEvent = { id, start, end ->
                        store.data.eventsOn(epochDay).firstOrNull { it.id == id }?.let { editOccurrence(epochDay, id, it.copy(start = start, end = end)) }
                    },
                    canUndo = store.canUndo, canRedo = store.canRedo,
                    onUndo = { store.undo() }, onRedo = { store.redo() },
                    saving = store.busy, onCreateEvent = { event ->
                        val day = epochDay
                        store.change("事件已创建", { it.addEvent(day, event) }, trackUndo = true)
                    }, message = store.notice, settings = data.settings, onTemplates = openTemplates,
                    onImportTemplate = { if (!store.busy) importingTemplate = epochDay to store.data },
                    onClear = { if (!store.busy) clearRequest = TemplateRequest("blankDay", null, epochDay, LocalDate.now().toEpochDay(), store.data) },
                    onCreatePrecisely = { event, repeat, done ->
                        val day = epochDay
                        store.change("安排已创建", { if (repeat != null) it.saveRepeatRule(null, repeat) else it.addEvent(day, event!!) }, true, done)
                    }
                )
                1 -> scheduleState.SaveableStateProvider("schedule") { SchedulePage(
                    date = LocalDate.ofEpochDay(epochDay), onDateChange = changeDate,
                    events = dayEvents, categories = data.categories, data = data,
                    mode = scheduleMode, onModeChange = { scheduleMode = it },
                    onOpenEvent = { day, id ->
                        // Resolve against the current source; never select by position or time.
                        if (store.data.eventsOn(day.toEpochDay()).any { it.id == id }) {
                            epochDay = day.toEpochDay()
                            selectedEventId = id
                            page = 0
                        }
                    },
                    onPlanDay = { selectedEventId = null; page = 0 }, onTemplates = openTemplates, onExport = { onScheduleExport(epochDay) }
                ) }
                2 -> SettingsPage(store, onExport, onImport, onManageCategories = { editTarget = "main" }, onManageTemplates = openTemplates, onManageRepeats = { managingRepeats = true })
            }
        }
        CompactBottomNavigation(page) {
            if (page != it) selectedEventId = null
            page = it
        }
    }
    importingTemplate?.let { (day, snapshot) ->
        ImportTemplateDialog(store, day, snapshot) { importingTemplate = null }
    }
    clearRequest?.let { pending ->
        TemplateConfirmDialog(pending, store.busy, store.notice, { clearRequest = null }) {
            store.change("基础安排已清空", {
                require(it == pending.snapshot) { "数据已变化，请取消后重试" }
                it.makeDayBlank(pending.day, replaceConfirmed = true)
            }, trackUndo = true, onSaved = { selectedEventId = null; clearRequest = null })
        }
    }
    if (managingRepeats) RepeatManager(store, epochDay, changeDate, { managingRepeats = false }, {
        repeatEdit = it
    })
    repeatScope?.let { request -> RepeatScopeDialog(store, request, { repeatScope = null }, {
        repeatEdit = it; repeatScope = null
    }) }
    repeatEdit?.let { request -> key(request) { RepeatEditor(store, request) { repeatEdit = null } } }
    if (managingTemplates) {
        TemplateManager(store, epochDay, changeDate, onClose = { managingTemplates = false },
            onDayChanged = { selectedEventId = null })
    }
    if (editTarget.isNotEmpty()) {
        CategoryManager(store, editTarget.takeUnless { it == "main" },
            onClose = { editTarget = "" }, onChildren = { editTarget = it },
            onParents = { editTarget = "main" })
    }
}

@Composable
private fun SchedulePage(
    date: LocalDate, onDateChange: (LocalDate) -> Unit,
    events: List<DemoEvent>, categories: List<Category>, data: AppData,
    mode: String, onModeChange: (String) -> Unit,
    onOpenEvent: (LocalDate, String) -> Unit, onPlanDay: () -> Unit, onTemplates: () -> Unit, onExport: () -> Unit
) {
    // Sorting is a display projection only. Equal starts retain the source order;
    // neither overlap layers nor the stored events are reordered or duplicated.
    val now = java.time.LocalTime.now()
    val minute = if (date == LocalDate.now()) now.hour * 60 + now.minute else 720
    val ordered = remember(events, data, minute) {
        compactOvernightRows(events, minute, { it.start }, { it.end }, { data.displaySeries(it, date.toEpochDay()) })
            .sortedBy { it.start }
    }
    val statisticsState = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().padding(horizontal = BricksDimensions.pageInset)
        .testTag("schedule-page")) {
        DateNavigation(date, onDateChange, menuItems = listOf(
            DateNavigationAction("模板管理", onTemplates, "schedule-templates"),
            DateNavigationAction("导出日程", onExport, "schedule-export")
        ))
        Spacer(Modifier.height(BricksDimensions.gap))
        Row(Modifier.fillMaxWidth().selectableGroup()
            .clip(RoundedCornerShape(BricksDimensions.medium)).background(NavBg).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("schedule" to "日程", "statistics" to "统计").forEach { (value, label) ->
                val selected = mode == value
                Box(Modifier.weight(1f).heightIn(min = 40.dp)
                    .testTag("schedule-mode-$value")
                    .clip(RoundedCornerShape(BricksDimensions.small))
                    .background(if (selected) Accent else Color.Transparent)
                    .selectable(selected, role = Role.Tab) { onModeChange(value) }
                    .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) Primary else Secondary, fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(BricksDimensions.gap * 2))
        when {
            mode == "statistics" -> statisticsState.SaveableStateProvider("statistics") {
                StatisticsPage(data, date, Modifier.weight(1f))
            }
            ordered.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth().testTag("schedule-empty"),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这一天还没有安排", color = Primary, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("去规划页，留一块时间给想做的事", color = Secondary, fontSize = 13.sp,
                    textAlign = TextAlign.Center)
                TextButton(onClick = onPlanDay) { Text("去规划") }
            }
            else -> key(date) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("schedule-list"),
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(bottom = BricksDimensions.pageInset),
                    verticalArrangement = Arrangement.spacedBy(BricksDimensions.gap * 1.5f)) {
                    itemsIndexed(ordered, key = { _, event -> event.id }) { _, event ->
                        ScheduleCard(event, categories) { onOpenEvent(date, event.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(event: DemoEvent, categories: List<Category>, onClick: () -> Unit) {
    // Archived categories remain resolvable for historical events, as in the matrix.
    val category = categories.firstOrNull { it.id == event.categoryId }
    val child = category?.children?.firstOrNull { it.id == event.child }
    val title = (category?.name ?: "未知分类") +
        if (event.child.isEmpty()) "" else "·${child?.name ?: "未知子分类"}"
    val color = child?.color ?: category?.color ?: Secondary
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .testTag("schedule-event-${event.id}")
        .clip(RoundedCornerShape(BricksDimensions.pill)).background(TrackBg)
        .clickable(role = Role.Button, onClickLabel = "在规划页选中此事件", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        // Keep the full stripe inside even the maximum 28dp outer corner.
        Box(Modifier.padding(start = 10.dp, top = 12.dp, bottom = 12.dp)
            .width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color)
            .testTag("schedule-stripe-${event.id}"))
        Row(Modifier.weight(1f).heightIn(min = (68 * LocalAppearance.current.spacingScale).dp.coerceAtLeast(56.dp))
            .padding(horizontal = BricksDimensions.pageInset, vertical = (8 * LocalAppearance.current.spacingScale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(durationLabel(event.end - event.start), color = Secondary, fontSize = 12.sp)
            }
            Text("${scheduleClock(event.start)}–${scheduleClock(event.end)}", color = Primary,
                fontSize = 14.sp, style = TextStyle(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

// Statistics are read-only projections of effective calendar-day occurrences.
// Clip each full occurrence to the requested date before aggregation. Its two
// day slices contribute disjoint minutes; never sum the full duration per view.
internal data class StatisticsRange(val start: Long, val end: Long) {
    init { require(start <= end) }
    val days: Long get() = end - start + 1
}
internal data class StatisticsEvent(val start: Int, val end: Int, val parent: String, val child: String = "")
internal data class StatisticsKey(val parent: String, val child: String)
internal data class StatisticsTotals(val planned: Long = 0, val covered: Long = 0) {
    operator fun plus(other: StatisticsTotals) = StatisticsTotals(planned + other.planned, covered + other.covered)
}
internal data class StatisticsDay(val day: Long, val totals: StatisticsTotals)
internal data class StatisticsReport(
    val range: StatisticsRange, val total: StatisticsTotals,
    val parents: Map<String, StatisticsTotals>, val children: Map<StatisticsKey, StatisticsTotals>,
    val daily: List<StatisticsDay>
)
internal object StatisticsMath {
    fun range(period: String, date: LocalDate): StatisticsRange {
        val start = when (period) {
            "day" -> date
            "week" -> date.minusDays((date.dayOfWeek.value - 1).toLong())
            "month" -> date.withDayOfMonth(1)
            "year" -> date.withDayOfYear(1)
            else -> error("Unknown statistics period")
        }
        val end = when (period) {
            "day" -> start
            "week" -> start.plusDays(6)
            "month" -> start.plusMonths(1).minusDays(1)
            else -> start.plusYears(1).minusDays(1)
        }
        return StatisticsRange(start.toEpochDay(), end.toEpochDay())
    }

    fun totals(events: List<StatisticsEvent>): StatisticsTotals {
        var planned = 0L
        var covered = 0L
        var right = 0
        events.sortedBy { it.start }.forEach { event ->
            require(event.start in 0..1439 && event.end in 1..1440 && event.end > event.start)
            planned += event.end - event.start
            covered += (event.end - maxOf(right, event.start)).coerceAtLeast(0)
            right = maxOf(right, event.end)
        }
        return StatisticsTotals(planned, covered)
    }

    fun aggregate(range: StatisticsRange, eventsOn: (Long) -> List<StatisticsEvent>): StatisticsReport {
        var total = StatisticsTotals()
        val parents = linkedMapOf<String, StatisticsTotals>()
        val children = linkedMapOf<StatisticsKey, StatisticsTotals>()
        val daily = mutableListOf<StatisticsDay>()
        for (day in range.start..range.end) {
            // Call the same source once per date, including dates without stored rows.
            val events = eventsOn(day)
            val dayTotal = totals(events)
            total += dayTotal
            daily += StatisticsDay(day, dayTotal)
            events.groupBy { it.parent }.forEach { (id, group) ->
                parents[id] = (parents[id] ?: StatisticsTotals()) + totals(group)
            }
            events.groupBy { StatisticsKey(it.parent, it.child) }.forEach { (id, group) ->
                children[id] = (children[id] ?: StatisticsTotals()) + totals(group)
            }
        }
        return StatisticsReport(range, total, parents, children, daily)
    }
}

private fun statisticsDuration(minutes: Long): String = "${minutes / 60}小时${minutes % 60}分"
private fun statisticsDate(day: Long): String = LocalDate.ofEpochDay(day).toString()

@Composable
private fun StatisticsPage(data: AppData, date: LocalDate, modifier: Modifier = Modifier) {
    var period by rememberSaveable { mutableStateOf("week") }
    var customStart by rememberSaveable { mutableLongStateOf(date.toEpochDay()) }
    var customEnd by rememberSaveable { mutableLongStateOf(date.toEpochDay()) }
    var choosingRange by rememberSaveable { mutableStateOf(false) }
    var parentId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDays by rememberSaveable { mutableStateOf(false) }
    val range = if (period == "custom") StatisticsRange(customStart, customEnd) else StatisticsMath.range(period, date)
    // Key the holder itself so a new range never renders the previous range's totals.
    val report by key(data, range) {
        produceState<StatisticsReport?>(null, data, range) {
            value = withContext(Dispatchers.Default) {
                val calculationContext = kotlinx.coroutines.currentCoroutineContext()
                StatisticsMath.aggregate(range) { day ->
                    calculationContext.ensureActive()
                    data.eventsOn(day).map { StatisticsEvent(it.start.coerceAtLeast(0), it.end.coerceAtMost(1440), it.categoryId, it.child) }
                }
            }
        }
    }
    var showInfo by remember { mutableStateOf(false) }
    val parent = data.categories.firstOrNull { it.id == parentId }
    BackHandler(enabled = parent != null && !choosingRange) { parentId = null }
    Column(modifier.fillMaxSize().testTag("statistics-page"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("day" to "日", "week" to "周", "month" to "月", "year" to "年", "custom" to "自定义").forEach { (value, label) ->
                Box(Modifier.weight(1f).heightIn(min = 44.dp).testTag("statistics-period-$value")
                    .clip(RoundedCornerShape(BricksDimensions.small))
                    .background(if (period == value) Accent else NavBg)
                    .selectable(period == value, role = Role.RadioButton) {
                        if (value == "custom") {
                            if (period != "custom") { customStart = range.start; customEnd = range.end }
                            choosingRange = true
                        } else period = value
                    }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 13.sp, color = if (period == value) Primary else Secondary)
                }
            }
        }
        Text(if (range.start == range.end) statisticsDate(range.start) else "${statisticsDate(range.start)} — ${statisticsDate(range.end)}",
            color = Secondary, fontSize = 12.sp, modifier = Modifier.testTag("statistics-range"))
        val result = report
        if (result == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("正在汇总…", color = Secondary)
            }
        } else key(range, parentId) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("statistics-list"),
                state = rememberLazyListState(), contentPadding = PaddingValues(bottom = BricksDimensions.pageInset),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "summary") {
                    SettingsGroup {
                        Box(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "统计口径") { showInfo = true }.padding(16.dp)) {
                            StatisticsDurationLine(result.total, emphasized = true)
                        }
                    }
                }
                if (result.total.planned == 0L) item(key = "empty") {
                    Text("这段时间还没有安排", color = Primary, fontSize = 17.sp,
                        modifier = Modifier.testTag("statistics-empty").padding(vertical = 16.dp))
                }
                item(key = "heading") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(parent?.name ?: "分类总览", Modifier.weight(1f), color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        if (parent != null) TextButton(onClick = { parentId = null }, modifier = Modifier.testTag("statistics-back")) { Text("返回") }
                    }
                }
                if (parent == null) {
                    val rows = data.categories.filter { (result.parents[it.id]?.planned ?: 0L) > 0 }
                    itemsIndexed(rows, key = { _, category -> "parent-${category.id}" }) { _, category ->
                        StatisticsCategoryRow(category.name + if (category.archived) "（已归档）" else "",
                            category.color, result.parents.getValue(category.id), result.total.planned,
                            "statistics-parent-${category.id}") { parentId = category.id }
                    }
                } else {
                    val parentTotal = result.parents[parent.id] ?: StatisticsTotals()
                    item(key = "parent-total") {
                        StatisticsCategoryRow("${parent.name}合计", parent.color, parentTotal,
                            parentTotal.planned, "statistics-parent-total")
                    }
                    val direct = result.children[StatisticsKey(parent.id, "")]
                    if (direct != null) item(key = "direct") {
                        StatisticsCategoryRow("仅主分类", parent.color, direct,
                            parentTotal.planned, "statistics-child-direct")
                    }
                    val rows = parent.children.filter { (result.children[StatisticsKey(parent.id, it.id)]?.planned ?: 0L) > 0 }
                    itemsIndexed(rows, key = { _, child -> "child-${child.id}" }) { _, child ->
                        StatisticsCategoryRow(child.name + if (child.archived) "（已归档）" else "",
                            child.color, result.children.getValue(StatisticsKey(parent.id, child.id)),
                            parentTotal.planned, "statistics-child-${child.id}")
                    }
                    if (parentTotal.planned == 0L) item(key = "parent-empty") {
                        Text("该分类在这段时间没有安排", color = Secondary)
                    }
                }
                item(key = "days-toggle") {
                    TextButton(onClick = { showDays = !showDays }, modifier = Modifier.testTag("statistics-days-toggle")) {
                        Text(if (showDays) "收起明细" else "逐日明细")
                    }
                }
                if (showDays) itemsIndexed(result.daily, key = { _, day -> "day-${day.day}" }) { _, day ->
                    Column(Modifier.fillMaxWidth().testTag("statistics-day-${day.day}")
                        .clip(RoundedCornerShape(BricksDimensions.small)).background(NavBg).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(statisticsDate(day.day), color = Primary, fontSize = 13.sp)
                        StatisticsDurationLine(day.totals)
                    }
                }
            }
        }
    }
    if (showInfo) BricksDialog(onDismissRequest = { showInfo = false }, title = { Text("统计口径") },
        text = { Text("计划：所有事件时长相加。覆盖：重叠时间只算一次。没有重叠时两者相同。\n占比按计划时长计算，各分类覆盖可能重叠。统计反映安排，不代表实际执行。") },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("知道了") } })
    if (choosingRange) StatisticsRangeDialog(StatisticsRange(customStart, customEnd),
        onDismiss = { choosingRange = false }, onConfirm = {
            customStart = it.start; customEnd = it.end; period = "custom"; choosingRange = false
        })
}

@Composable
private fun StatisticsDurationLine(totals: StatisticsTotals, emphasized: Boolean = false) {
    Text(if (totals.planned == totals.covered) "计划 / 覆盖 ${statisticsDuration(totals.planned)}"
        else "计划 ${statisticsDuration(totals.planned)}  ·  覆盖 ${statisticsDuration(totals.covered)}",
        color = if (emphasized) Primary else Secondary, fontSize = if (emphasized) 15.sp else 12.sp,
        fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier.testTag(if (emphasized) "statistics-totals" else "statistics-duration"))
}

@Composable
private fun StatisticsCategoryRow(title: String, color: Color, totals: StatisticsTotals,
    denominator: Long, tag: String, onClick: (() -> Unit)? = null) {
    val fraction = if (denominator == 0L) 0f else (totals.planned.toDouble() / denominator).toFloat().coerceIn(0f, 1f)
    val action = if (onClick != null) Modifier.clickable(role = Role.Button,
        onClickLabel = "查看子分类", onClick = onClick) else Modifier
    Column(Modifier.fillMaxWidth().testTag(tag).clip(RoundedCornerShape(BricksDimensions.medium))
        .background(TrackBg).then(action).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(color))
            Text(title, color = Primary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${String.format(Locale.ROOT, "%.1f", fraction * 100)}%",
                color = Secondary, fontSize = 12.sp)
            if (onClick != null) ChevronIcon()
        }
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(NavBg)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
        }
        StatisticsDurationLine(totals)
    }
}

@Composable
private fun StatisticsRangeDialog(initial: StatisticsRange, onDismiss: () -> Unit, onConfirm: (StatisticsRange) -> Unit) {
    var first by rememberSaveable { mutableLongStateOf(initial.start) }
    var last by rememberSaveable { mutableLongStateOf(initial.end) }
    BricksDialog(onDismissRequest = onDismiss, title = { Text("自定义日期") }, text = {
        Column {
            DateValueRow("开始", first) { first = it }
            DateValueRow("结束", last) { last = it }
            if (last < first) Text("结束日期不能早于开始日期", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = first <= last, onClick = { onConfirm(StatisticsRange(first, last)) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun PlanningPage(
    externalCreate: Boolean = false, onExternalCreateConsumed: () -> Unit = {},
    date: LocalDate, onDateChange: (LocalDate) -> Unit,
    categories: List<Category>, activeCategory: Category?, child: String,
    onMainSelect: (String) -> Unit, onChildSelect: (String) -> Unit,
    onEditMain: () -> Unit, onEditChildren: () -> Unit, events: List<DemoEvent>,
    onCreateEvent: (DemoEvent) -> Unit, message: String, settings: DisplaySettings, saving: Boolean,
    selectedEventId: String?, onSelectEvent: (String?) -> Unit,
    onDeleteEvent: (String) -> Unit, onEditEvent: (String, Int, Int) -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit,
    onTemplates: () -> Unit, onClear: () -> Unit, onImportTemplate: () -> Unit,
    onCreatePrecisely: (DemoEvent?, RepeatVersion?, () -> Unit) -> Unit
) {
    var creatingPrecisely by remember(date) { mutableStateOf(false) }
    LaunchedEffect(externalCreate) {
        if (externalCreate) { creatingPrecisely = true; onExternalCreateConsumed() }
    }
    var overlapCycle by remember(date, events) { mutableStateOf<OverlapRegion?>(null) }
    LaunchedEffect(selectedEventId) { if (selectedEventId == null) overlapCycle = null }
    val cyclePosition = overlapCycle?.takeIf { selectedEventId in it.ids }?.let {
        "${it.ids.indexOf(selectedEventId) + 1}/${it.ids.size}"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = BricksDimensions.pageInset)) {
        // The toolbar occupies layout space, never overlays the final matrix row.
        // Small windows/large fonts can scroll the content above the fixed toolbar.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeight = maxHeight
            Layout(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = {
                Column {
                    DateNavigation(date, onDateChange, menuItems = listOf(
                        DateNavigationAction("导入模板", onImportTemplate, "planning-import-template"),
                        DateNavigationAction("模板管理", onTemplates, "planning-templates"),
                        DateNavigationAction("清空当日", onClear, "planning-clear")
                    ))
                    PlanningSummary(events, canUndo, canRedo, onUndo, onRedo)
                    Spacer(Modifier.height(4.dp))
                    CategoryBar(categories, activeCategory, child, onMainSelect, onChildSelect, onEditMain, onEditChildren, settings)
                    Spacer(Modifier.height(8.dp))
                }
                TimeMatrix(events, categories, activeCategory, child, onCreateEvent, selectedEventId, onSelectEvent, onEditEvent,
                    date.toEpochDay(), overlapCycle, { overlapCycle = it }, saving)
            }) { measurables, constraints ->
                val header = measurables[0].measure(Constraints(maxWidth = constraints.maxWidth))
                val bottomGap = 8.dp.roundToPx()
                // Allocate remaining space to all eight rows; retain a usable minimum.
                // Only unusually short windows/large fonts need the scroll fallback.
                val matrixHeight = (viewportHeight.roundToPx() - header.height - bottomGap)
                    .coerceIn((8 * 24.dp.roundToPx() + 7 * 5.dp.roundToPx()),
                        (8 * 44.dp.roundToPx() + 7 * 5.dp.roundToPx()))
                val matrix = measurables[1].measure(Constraints.fixed(constraints.maxWidth, matrixHeight))
                layout(constraints.maxWidth, header.height + matrix.height + bottomGap) {
                    header.placeRelative(0, 0)
                    matrix.placeRelative(0, header.height)
                }
            }
        }
        EventToolbar(events.firstOrNull { it.id == selectedEventId }, categories,
            onDeleteEvent, onEditEvent, onSelectEvent, cyclePosition, { overlapCycle = null },
            canCreate = categories.any { !it.archived }, onNew = { creatingPrecisely = true }, viewDay = date.toEpochDay())
        Spacer(Modifier.height(8.dp))
    }
    if (creatingPrecisely) {
        val initialStart = remember {
            val now = java.time.LocalTime.now()
            val earliest = if (date == LocalDate.now()) (now.hour * 60 + now.minute) + 1 else 540
            suggestedNewStart(events, earliest)
        }
        PreciseTimeDialog("精确新建事件", initialStart, initialStart + 60,
            categories = categories, initialCategoryId = activeCategory?.id.orEmpty(), initialChildId = child,
            creationDay = date.toEpochDay(), displayDay = date.toEpochDay(), busy = saving, notice = message,
            onSaveRepeat = { version -> onCreatePrecisely(null, version) { creatingPrecisely = false } },
            onDismiss = { creatingPrecisely = false },
            onSave = { start, end, categoryId, childId ->
                onCreatePrecisely(DemoEvent(start, end, categoryId, childId).storedFromView(date.toEpochDay(), date.toEpochDay()), null) { creatingPrecisely = false }
            })
    }
}

// ISO weekday values are Monday = 1 through Sunday = 7, including year boundaries.
private fun mondayOf(date: LocalDate): LocalDate =
    date.minusDays((date.dayOfWeek.value - 1).toLong())

private data class DateNavigationAction(
    val label: String,
    val onClick: () -> Unit,
    val testTag: String
)

// A small calendar-today glyph inside a full 48 dp touch target, shared by every date header.
@Composable
private fun TodayIconButton(onClick: () -> Unit) {
    val ink = Secondary
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)
        .testTag("date-today").semantics { contentDescription = "回到今天" }) {
        Canvas(Modifier.size(24.dp)) {
            val unit = size.width / 24f
            val stroke = 1.7f * unit
            drawRoundRect(ink, topLeft = Offset(3f * unit, 5f * unit),
                size = Size(18f * unit, 16f * unit), cornerRadius = CornerRadius(2.5f * unit),
                style = Stroke(stroke))
            drawLine(ink, Offset(3f * unit, 10f * unit), Offset(21f * unit, 10f * unit), stroke)
            drawLine(ink, Offset(8f * unit, 3f * unit), Offset(8f * unit, 7f * unit), stroke, StrokeCap.Round)
            drawLine(ink, Offset(16f * unit, 3f * unit), Offset(16f * unit, 7f * unit), stroke, StrokeCap.Round)
            drawRoundRect(ink, topLeft = Offset(7f * unit, 13f * unit),
                size = Size(4f * unit, 4f * unit), cornerRadius = CornerRadius(.7f * unit))
        }
    }
}

@Composable
private fun DateNavigationMoreMenu(items: List<DateNavigationAction>, date: LocalDate) {
    // Changing the selected day or leaving this page must not leave an old menu open.
    var expanded by remember(date) { mutableStateOf(false) }
    val ink = Secondary
    Box {
        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp)
            .testTag("date-more").semantics { contentDescription = "更多" }) {
            Canvas(Modifier.size(24.dp)) {
                val unit = size.width / 24f
                listOf(5f, 12f, 19f).forEach { y ->
                    drawCircle(ink, radius = 1.8f * unit, center = Offset(12f * unit, y * unit))
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 160.dp).testTag("date-more-menu"),
            shape = RoundedCornerShape(16.dp), containerColor = Color(0xFF181818),
            tonalElevation = 0.dp, shadowElevation = 8.dp) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, color = Color(0xFFF2F2F2), fontSize = 14.sp) },
                    onClick = { expanded = false; item.onClick() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(item.testTag))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateNavigation(
    date: LocalDate, onDateChange: (LocalDate) -> Unit,
    menuItems: List<DateNavigationAction> = emptyList()
) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    if (pickingDate) DateWheelDialog(date, { pickingDate = false }) { onDateChange(it); pickingDate = false }
    val density = LocalDensity.current
    val monthFormatter = remember { DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA) }
    val spokenFormatter = remember { DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA) }
    val weekStart = mondayOf(date)
    val today = LocalDate.now()
    val latestOnDateChange by rememberUpdatedState(onDateChange)
    val swipeThreshold = with(density) { 40.dp.toPx() }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                .clip(RoundedCornerShape(BricksDimensions.small))
                .clickable(role = Role.Button, onClickLabel = "选择日期") {
                    pickingDate = true
                }.padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(date.format(monthFormatter), color = Primary, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)
            }
            TodayIconButton { onDateChange(LocalDate.now()) }
            if (menuItems.isNotEmpty()) DateNavigationMoreMenu(menuItems, date)
        }
        Row(Modifier.fillMaxWidth().testTag("week-navigation")
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("上一周") {
                        onDateChange(date.minusWeeks(1)); true
                    },
                    CustomAccessibilityAction("下一周") {
                        onDateChange(date.plusWeeks(1)); true
                    }
                )
            }
            .pointerInput(date, swipeThreshold) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onHorizontalDrag = { change, amount ->
                        distance += amount
                        change.consume()
                    },
                    onDragEnd = {
                        // One completed swipe changes one week, keeping the weekday selected.
                        if (distance <= -swipeThreshold) latestOnDateChange(date.plusWeeks(1))
                        else if (distance >= swipeThreshold) latestOnDateChange(date.minusWeeks(1))
                        distance = 0f
                    },
                    onDragCancel = { distance = 0f }
                )
            }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                val day = weekStart.plusDays(index.toLong())
                val selected = day == date
                val isToday = day == today
                val shape = RoundedCornerShape(BricksDimensions.medium)
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                    .background(if (selected) LocalBricksColors.current.dateSelected else Color.Transparent)
                    .border(1.dp,
                        if (isToday && !selected) LocalBricksColors.current.todayBorder else Color.Transparent, shape)
                    .selectable(selected = selected, role = Role.RadioButton) { onDateChange(day) }
                    .semantics {
                        contentDescription = day.format(spokenFormatter) + if (isToday) "，今天" else ""
                    }
                    .padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
                    Text(label, color = if (selected) Primary else Secondary,
                        fontSize = 11.sp, maxLines = 1)
                    Text(day.dayOfMonth.toString(),
                        color = if (selected || day.month == date.month) Primary else Secondary,
                        fontSize = 18.sp, maxLines = 1,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun CategoryBar(
    categories: List<Category>, activeCategory: Category?, child: String,
    onMainSelect: (String) -> Unit, onChildSelect: (String) -> Unit,
    onEditMain: (() -> Unit)?, onEditChildren: (() -> Unit)?, settings: DisplaySettings
) {
    Column(verticalArrangement = Arrangement.spacedBy(BricksDimensions.gap)) {
        CategoryBand(settings.mainRows, 34.dp, "管理主分类", onEditMain) {
            categories.filterNot { it.archived }.forEach { category ->
                key(category.id) {
                    MainCategoryChip(category.name, category.color, category.id == activeCategory?.id) {
                        onMainSelect(category.id)
                    }
                }
            }
        }
        // Keep the same reserved height, including the empty state and parents with no children.
        key(activeCategory?.id) {
            CategoryBand(settings.childRows, 30.dp, "管理${activeCategory?.name ?: "主分类"}的子分类",
                if (activeCategory == null) onEditMain else onEditChildren) {
                if (activeCategory == null) {
                    Text("暂无可用分类", color = Secondary, fontSize = 11.sp, maxLines = 1)
                } else {
                    ChildCategoryChip("仅主分类", activeCategory.color, child.isEmpty()) { onChildSelect("") }
                    activeCategory.children.filterNot { it.archived }.forEach { item ->
                        key(item.id) {
                            ChildCategoryChip(item.name, item.color, child == item.id) { onChildSelect(item.id) }
                        }
                    }
                }
            }
        }
    }
}

// Preserve list order; choose the split minimizing the wider row's natural text width.
// More/longer categories expand horizontally, never create a third row.
private fun fixedCategoryRows(widths: List<Int>, rows: Int, gap: Int): List<IntRange> {
    require(rows in 1..2)
    if (widths.isEmpty()) return emptyList()
    if (rows == 1 || widths.size == 1) return listOf(widths.indices)
    val prefix = IntArray(widths.size + 1)
    widths.forEachIndexed { index, width -> prefix[index + 1] = prefix[index] + width }
    fun span(start: Int, end: Int) = prefix[end] - prefix[start] + gap * (end - start - 1)
    val split = (1 until widths.size).minWithOrNull(compareBy<Int>(
        { maxOf(span(0, it), span(it, widths.size)) },
        { abs(span(0, it) - span(it, widths.size)) },
        { abs(it - (widths.size - it)) }, { -it }
    ))!!
    return listOf(0 until split, split until widths.size)
}

private fun filledCategoryWidths(widths: List<Int>, available: Int): List<Int> {
    if (widths.isEmpty()) return emptyList()
    val total = widths.sum().coerceAtLeast(1)
    val extra = (available - total).coerceAtLeast(0)
    var cumulative = 0L
    var allocated = 0
    return widths.map { width ->
        cumulative += width
        val next = (extra.toLong() * cumulative / total).toInt()
        (width + next - allocated).also { allocated = next }
    }
}

@Composable
private fun CategoryBand(
    rows: Int, rowHeight: Dp, editLabel: String, onEdit: (() -> Unit)?, content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val spacing = LocalAppearance.current.spacingScale
    val bandGap = BricksDimensions.gap
    // Content-independent height prevents a parent switch from moving the matrix.
    val height = with(density) { maxOf(rowHeight * spacing, 16.sp.toDp() + 14.dp) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewport = with(density) { maxWidth.roundToPx() }
        Layout(content = {
            content()
            // The editor is the final measurable and participates in the same partition.
            if (onEdit != null) EditCategoryButton(height, editLabel, onEdit)
        }, modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup()) { measurables, constraints ->
            val gap = (5.dp * spacing).roundToPx()
            val rowGap = bandGap.roundToPx()
            val itemHeight = height.roundToPx()
            val preferred = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtLeast(36.dp.roundToPx()) }
            val partition = fixedCategoryRows(preferred, rows, gap)
            val width = maxOf(viewport, partition.maxOfOrNull { row ->
                row.sumOf { preferred[it] } + gap * (row.count() - 1)
            } ?: 0)
            val itemWidths = IntArray(measurables.size)
            partition.forEach { row ->
                val sizes = filledCategoryWidths(row.map { preferred[it] }, width - gap * (row.count() - 1))
                row.forEachIndexed { offset, index -> itemWidths[index] = sizes[offset] }
            }
            val items = measurables.mapIndexed { index, measurable ->
                measurable.measure(Constraints.fixed(itemWidths[index], itemHeight))
            }
            layout(width, constraints.constrainHeight(rows * itemHeight + (rows - 1) * rowGap)) {
                partition.forEachIndexed { rowIndex, row ->
                    var x = 0
                    row.forEach { index ->
                        items[index].placeRelative(x, rowIndex * (itemHeight + rowGap))
                        x += itemWidths[index] + gap
                    }
                }
            }
        }
    }
}


@Composable
private fun MainCategoryChip(
    label: String, color: Color, selected: Boolean, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(BricksDimensions.pill)
    Box(Modifier.heightIn(min = 34.dp).widthIn(min = 36.dp).clip(shape)
        .background(if (selected) color else LocalBricksColors.current.chip)
        .border(1.dp, if (selected) Primary.copy(alpha = .72f) else Color.Transparent, shape)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 7.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) Color.White else Secondary,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeight = 16.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)),
            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ChildCategoryChip(
    label: String, color: Color, selected: Boolean, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(BricksDimensions.pill)
    Box(Modifier.heightIn(min = 30.dp).widthIn(min = 36.dp).clip(shape)
        .background(if (selected) color.copy(alpha = .32f) else Color.Transparent)
        .border(1.dp,
            if (selected) color.copy(alpha = .95f) else LocalBricksColors.current.outline, shape)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 7.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) Primary else Secondary,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeight = 16.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)),
            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun EditCategoryButton(rowHeight: Dp = 30.dp, label: String, onClick: () -> Unit) {
    val ink = Secondary
    val shape = RoundedCornerShape(BricksDimensions.pill)
    Box(Modifier.heightIn(min = rowHeight).widthIn(min = 36.dp).clip(shape)
        .border(1.dp, LocalBricksColors.current.outline, shape)
        .semantics { contentDescription = label }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            drawLine(ink, Offset(size.width * .28f, size.height * .70f),
                Offset(size.width * .73f, size.height * .25f),
                strokeWidth = 4.dp.toPx(), cap = StrokeCap.Butt)
            drawLine(ink, Offset(size.width * .22f, size.height * .76f),
                Offset(size.width * .16f, size.height * .82f),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink, Offset(size.width * .79f, size.height * .19f),
                Offset(size.width * .84f, size.height * .14f),
                strokeWidth = 4.dp.toPx(), cap = StrokeCap.Butt)
        }
    }
}

private fun clockMinute(value: Int): String {
    if (value == 1440) return "24:00"
    val day = Math.floorDiv(value, 1440)
    val minute = Math.floorMod(value, 1440)
    return (when { day == -1 -> "前日"; day == 1 -> "次日"; day != 0 -> "${day}日"; else -> "" }) +
        "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
}

// Interval union: duplicate, nested and intersecting events count only once.
private fun scheduledMinutes(events: List<DemoEvent>): Int {
    var covered = 0
    var right = 0
    events.sortedBy { it.start }.forEach { event ->
        val start = event.start.coerceIn(0, 1440)
        val end = event.end.coerceIn(0, 1440)
        if (end > start) {
            covered += (end - maxOf(start, right)).coerceAtLeast(0)
            right = maxOf(right, end)
        }
    }
    return covered
}

private fun durationLabel(minutes: Int): String = "${minutes / 60}小时${minutes % 60}分"

private enum class PlanningIcon { Undo, Redo, Edit, Delete, Close, Add }

@Composable
private fun PlanningIconButton(
    icon: PlanningIcon, label: String, enabled: Boolean = true,
    background: Color = LocalBricksColors.current.track,
    ink: Color = Primary, onClick: () -> Unit
) {
    val tint = if (enabled) ink else ink.copy(alpha = .30f)
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(BricksDimensions.pill))
        .background(if (enabled) background else background.copy(alpha = .45f))
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            fun point(x: Float, y: Float) = Offset(size.width * x / 24f, size.height * y / 24f)
            fun line(x: Float, y: Float, ex: Float, ey: Float) =
                drawLine(tint, point(x, y), point(ex, ey), 2.dp.toPx(), StrokeCap.Round)
            when (icon) {
                PlanningIcon.Add -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
                PlanningIcon.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                PlanningIcon.Edit -> {
                    line(5f, 16f, 16f, 5f); line(8f, 19f, 19f, 8f)
                    line(16f, 5f, 19f, 8f); line(5f, 16f, 4f, 20f); line(4f, 20f, 8f, 19f)
                }
                PlanningIcon.Delete -> {
                    line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f)
                    line(6f, 6f, 7f, 21f); line(7f, 21f, 17f, 21f); line(17f, 21f, 18f, 6f)
                    line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
                }
                PlanningIcon.Undo, PlanningIcon.Redo -> {
                    fun x(value: Float) = if (icon == PlanningIcon.Redo) 24f - value else value
                    line(x(9f), 4f, x(4f), 9f); line(x(4f), 9f, x(9f), 14f)
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(point(x(4f), 9f).x, point(x(4f), 9f).y)
                        lineTo(point(x(14f), 9f).x, point(x(14f), 9f).y)
                        cubicTo(point(x(22f), 9f).x, point(x(22f), 9f).y,
                            point(x(22f), 20f).x, point(x(22f), 20f).y,
                            point(x(14f), 20f).x, point(x(14f), 20f).y)
                        lineTo(point(x(11f), 20f).x, point(x(11f), 20f).y)
                    }
                    drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
private fun PlanningSummary(events: List<DemoEvent>, canUndo: Boolean, canRedo: Boolean,
    onUndo: () -> Unit, onRedo: () -> Unit) {
    val occupied = remember(events) { scheduledMinutes(events) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Text("已安排 ${durationLabel(occupied)}", color = Primary, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1)
            Text("留白 ${durationLabel(1440 - occupied)}", color = Secondary, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1)
        }
        PlanningIconButton(PlanningIcon.Undo, "撤销", canUndo, onClick = onUndo)
        PlanningIconButton(PlanningIcon.Redo, "重做", canRedo, onClick = onRedo)
    }
}

@Composable
private fun EventToolbar(
    event: DemoEvent?, categories: List<Category>, onDelete: (String) -> Unit,
    onEdit: (String, Int, Int) -> Unit, onDeselect: (String?) -> Unit,
    cyclePosition: String? = null, onResetCycle: () -> Unit = {},
    canCreate: Boolean = true, onNew: () -> Unit = {}, viewDay: Long? = null
) {
    var editing by remember(event?.id) { mutableStateOf(false) }
    val category = categories.firstOrNull { it.id == event?.categoryId }
    val childName = category?.children?.firstOrNull { it.id == event?.child }?.name
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val detailWidth = with(density) {
            measurer.measure("00:00–24:00 · 24小时59分", TextStyle(fontSize = 11.sp)).size.width.toDp()
        }
        // This breakpoint uses a constant worst-case time label, not the selected event.
        val stacked = maxWidth < detailWidth + 188.dp
        val textHeight = with(density) { 40.sp.toDp() }
        val toolbarHeight = if (stacked) textHeight + 76.dp else (textHeight + 24.dp).coerceAtLeast(72.dp)
        val surface = Modifier.fillMaxWidth().height(toolbarHeight).testTag("event-toolbar")
            .clip(RoundedCornerShape(BricksDimensions.medium))
            .background(LocalBricksColors.current.chip).padding(12.dp)
        val details: @Composable (Modifier) -> Unit = { modifier ->
            if (event != null) {
                key(event.id) {
                    Column(modifier) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text((category?.name ?: "分类") + (childName?.let { "·$it" } ?: ""),
                                modifier = Modifier.testTag("toolbar-event-name"),
                                color = Primary, fontSize = 12.sp, lineHeight = 20.sp, maxLines = 1)
                            if (cyclePosition != null) Text(cyclePosition, color = Secondary,
                                fontSize = 10.sp, maxLines = 1,
                                modifier = Modifier.padding(start = 4.dp).testTag("overlap-position")
                                    .semantics { contentDescription = "重叠层序 $cyclePosition" })
                        }
                        Text(event.timeLabel(),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            color = Secondary, fontSize = 11.sp, lineHeight = 20.sp, maxLines = 1)
                    }
                }
            } else {
                Column(modifier) {
                    Text("给下一件事，留一块时间", color = Primary, fontSize = 12.sp, lineHeight = 20.sp, maxLines = 1,
                        modifier = Modifier.horizontalScroll(rememberScrollState()))

                }
            }
        }
        val actions: @Composable () -> Unit = {
            if (event != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlanningIconButton(PlanningIcon.Edit, "精确编辑", background = MaterialTheme.colorScheme.primaryContainer,
                        ink = MaterialTheme.colorScheme.primary) { onResetCycle(); editing = true }
                    PlanningIconButton(PlanningIcon.Delete, "删除事件", background = lerp(NavBg, Color(0xFFAE6666), .24f),
                        ink = readableAccent(Color(0xFFCF9292), NavBg, NavBg.luminance() < .5f)) { onDelete(event.id) }
                    PlanningIconButton(PlanningIcon.Close, "取消选中") { onDeselect(null) }
                }
            } else {
                PlanningIconButton(PlanningIcon.Add, "精确新建", enabled = canCreate,
                    background = MaterialTheme.colorScheme.primary, ink = MaterialTheme.colorScheme.onPrimary,
                    onClick = onNew)
            }
        }
        if (stacked) {
            // Narrow phones/large fonts keep full time information above right-aligned controls.
            Box(surface) {
                Column {
                    details(Modifier.fillMaxWidth().height(textHeight))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { actions() }
                }
            }
        } else {
            Row(surface, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                details(Modifier.weight(1f))
                actions()
            }
        }
    }
    if (editing && event != null) {
        PreciseTimeDialog("精确编辑时间", event.start, event.end,
            displayDay = viewDay, allowDateMove = viewDay != null,
            onDismiss = { editing = false },
            onSave = { start, end, _, _ -> onEdit(event.id, start, end); editing = false })
    }
}

// Shared by precise edit and precise create; keep minute-level compatibility.
private fun parsePreciseMinute(text: String): Int? {
    val bits = text.trim().split(':')
    if (bits.size != 2) return null
    val hour = bits[0].toIntOrNull() ?: return null
    val minute = bits[1].toIntOrNull() ?: return null
    if (hour !in 0..24 || minute !in 0..59 || (hour == 24 && minute != 0)) return null
    return hour * 60 + minute
}

private const val MAX_EVENT_MINUTES = 366 * 1440

private fun validPreciseRange(start: Int?, end: Int?): Boolean =
    start != null && end != null && start in 0..1439 && end in 1..MAX_EVENT_MINUTES && start < end

private fun suggestedNewStart(events: List<DemoEvent>, earliestMinute: Int): Int {
    val anchor = (((earliestMinute.coerceIn(0, 1440) + 29) / 30) * 30).coerceAtMost(1380)
    return (anchor..1380 step 30).firstOrNull { start ->
        events.none { it.start < start + 60 && it.end > start }
    } ?: anchor // A full day still permits an overlapping, editable one-hour default.
}

@Composable
private fun PreciseTimeDialog(
    title: String, initialStart: Int, initialEnd: Int,
    categories: List<Category>? = null, initialCategoryId: String = "", initialChildId: String = "",
    creationDay: Long? = null, busy: Boolean = false, notice: String = "",
    displayDay: Long? = null, allowDateMove: Boolean = false,
    onSaveRepeat: ((RepeatVersion) -> Unit)? = null,
    onDismiss: () -> Unit, onSave: (Int, Int, String, String) -> Unit
) {
    val referenceDay = displayDay ?: creationDay ?: LocalDate.now().toEpochDay()
    var start by rememberSaveable { mutableIntStateOf(Math.floorMod(initialStart, 1440)) }
    var end by rememberSaveable { mutableIntStateOf(initialEnd - Math.floorDiv(initialStart, 1440) * 1440) }
    var categoryId by rememberSaveable { mutableStateOf(initialCategoryId.ifEmpty { categories?.firstOrNull { !it.archived }?.id.orEmpty() }) }
    var childId by rememberSaveable { mutableStateOf(initialChildId) }
    var mode by rememberSaveable { mutableStateOf("none") }
    var mask by rememberSaveable { mutableIntStateOf(127) }
    var first by rememberSaveable { mutableLongStateOf(referenceDay + Math.floorDiv(initialStart, 1440)) }
    var last by rememberSaveable { mutableStateOf<Long?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val initialNotice = remember { notice }
    CompactBricksDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (categories != null) CompactCategoryChoice(categories, categoryId, childId, !busy) { parent, child -> categoryId = parent; childId = child }
            TimeRangeRows(start, end, !busy, { value ->
                val shift = Math.floorDiv(value, 1440)
                first += shift; start = Math.floorMod(value, 1440); end -= shift * 1440
            }, { end = it }, baseDay = first, allowStartDate = true)
            if (onSaveRepeat != null) {
                ValueRow("重复", if (mode == "none") "不重复" else if (mode == "daily") "每天" else repeatDaysLabel(weekdaySet(mask)), !busy) { expanded = !expanded }
                if (expanded) RepeatOptions(mode, { mode = it }, mask, { mask = it }, first, last, { last = it }, true, !busy)
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            if (!busy && notice != initialNotice && notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        try {
            require(validPreciseRange(Math.floorMod(start, 1440), end - Math.floorDiv(start, 1440) * 1440)) { "结束须晚于开始，最多占用366个日期" }
            require(categories == null || categories.any { it.id == categoryId && !it.archived }) { "请选择可用分类" }
            if (mode == "none" || onSaveRepeat == null) {
                val offset = if (allowDateMove || creationDay != null) Math.toIntExact((first - referenceDay) * 1440) else 0
                onSave(start + offset, end + offset, categoryId, childId)
            }
            else {
                val days = if (mode == "daily") (1..7).toSet() else weekdaySet(mask)
                require(days.isNotEmpty()) { "请选择至少一个星期" }
                require(last == null || last!! >= first) { "结束日期不能早于开始日期" }
                val category = categories!!.first { it.id == categoryId }
                val label = category.name + (category.children.firstOrNull { it.id == childId }?.let { "·${it.name}" } ?: "")
                onSaveRepeat(RepeatVersion(first, 1, label.take(24), DemoEvent(start, end, categoryId, childId), first, last, days))
            }
        } catch (failure: IllegalArgumentException) { error = failure.message ?: "请检查输入" }
    }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

// Map a drag to a five-minute boundary, including 24:00. Gaps choose the nearest row.
private fun gestureMinute(position: Offset, matrix: IntSize, density: Float): Int {
    val gap = 5f * density
    val rowHeight = (matrix.height - 7 * gap) / 8f
    val row = ((position.y + gap / 2) / (rowHeight + gap)).toInt().coerceIn(0, 7)
    val label = 27f * density
    val fraction = ((position.x - label) / (matrix.width - label).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (row * 180 + (fraction * 36).roundToInt() * 5).coerceIn(0, 1440)
}

private fun transformedEvent(event: DemoEvent, mode: Int, anchor: Int, minute: Int): DemoEvent = when (mode) {
    1 -> {
        val delta = ((minute - anchor) / 5f).roundToInt() * 5
        val duration = event.end - event.start
        // Keep the complete interval attached while dragging any daily slice.
        val raw = event.start + delta
        val maxStartMinute = minOf(1439, MAX_EVENT_MINUTES - duration)
        val start = Math.floorDiv(raw, 1440) * 1440 + Math.floorMod(raw, 1440).coerceAtMost(maxStartMinute)
        event.copy(start = start, end = start + duration)
    }
    2 -> {
        val earliest = event.end - MAX_EVENT_MINUTES
        event.copy(start = minute.coerceIn(maxOf(0, earliest), minOf(1439, event.end - 1)))
    }
    else -> event.copy(end = minute.coerceIn(maxOf(1, event.start + 1), minOf(1440, Math.floorDiv(event.start, 1440) * 1440 + MAX_EVENT_MINUTES)))
}

// Array order is the persistent layer order; selection and previews never reorder it.
private fun stableDayEvents(defaults: List<DemoEvent>, own: List<DemoEvent>, removed: List<String>): List<DemoEvent> {
    val replacements = own.associateBy { it.id }
    val defaultIds = defaults.map { it.id }.toSet()
    return defaults.mapNotNull { replacements[it.id] ?: it.takeUnless { e -> e.id in removed } } +
        own.filterNot { it.id in defaultIds }
}

private data class OverlapRegion(val row: Int, val start: Int, val end: Int, val ids: List<String>)

private fun matrixRegions(events: List<DemoEvent>, row: Int): List<OverlapRegion> {
    val start = row * 180; val end = start + 180
    val boundaries = (listOf(start, end) + events.flatMap { listOf(it.start, it.end) }
        .filter { it > start && it < end }).distinct().sorted()
    return boundaries.zipWithNext { a, b ->
        OverlapRegion(row, a, b, events.asReversed().filter { it.start < b && it.end > a }.map { it.id })
    }
}

// Hit testing uses the exact time, not the creation grid: manually entered one-minute
// events and both sides of a non-five-minute boundary remain separately selectable.
private fun exactMatrixMinute(position: Offset, matrix: IntSize, density: Float): Float? {
    val label = 27f * density
    val height = (matrix.height - 35f * density) / 8f
    if (matrix.width <= label || height <= 0 || position.x < label || position.x >= matrix.width ||
        position.y < 0 || position.y >= matrix.height) return null
    val row = (position.y / (height + 5f * density)).toInt().coerceIn(0, 7)
    if (position.y - row * (height + 5f * density) >= height) return null
    return row * 180 + (position.x - label) / (matrix.width - label) * 180
}

private fun nextRegionId(region: OverlapRegion, previous: OverlapRegion?, selectedId: String?): String? {
    if (region.ids.isEmpty()) return null
    val index = if (region == previous && selectedId in region.ids)
        (region.ids.indexOf(selectedId) + 1) % region.ids.size else 0
    return region.ids[index]
}

private fun endpointPosition(event: DemoEvent, end: Boolean, matrix: IntSize, density: Float): Offset {
    val minute = if (end) event.end.coerceAtMost(1440) else event.start.coerceAtLeast(0)
    val startRow = (event.start / 180).coerceIn(0, 7)
    val endRow = ((event.end - 1) / 180).coerceIn(0, 7)
    val row = if (end) endRow else startRow
    val rowHeight = (matrix.height - 35f * density) / 8f
    val label = 27f * density
    // Only grips which visually collide on the same row use a local vertical offset.
    val narrow = startRow == endRow && (matrix.width - label) * (event.end - event.start) / 180f < 12f * density
    val yFraction = if (narrow) (if (end) .72f else .28f) else .5f
    return Offset(label + (matrix.width - label) * (minute - row * 180) / 180f,
        row * (rowHeight + 5f * density) + rowHeight * yFraction)
}

@Composable
private fun TimeMatrix(
    events: List<DemoEvent>, categories: List<Category>, activeCategory: Category?, child: String,
    onCreateEvent: (DemoEvent) -> Unit, selectedId: String?, onSelect: (String?) -> Unit,
    onEditEvent: (String, Int, Int) -> Unit, day: Long,
    cycle: OverlapRegion?, onCycle: (OverlapRegion?) -> Unit, saving: Boolean
) {
    val density = LocalDensity.current.density
    val latestSelect by rememberUpdatedState(onSelect)
    val latestEdit by rememberUpdatedState(onEditEvent)
    val latestCreate by rememberUpdatedState(onCreateEvent)
    val latestSelected by rememberUpdatedState(selectedId)
    val latestCycle by rememberUpdatedState(cycle)
    val latestSetCycle by rememberUpdatedState(onCycle)
    var matrixSize by remember { mutableStateOf(IntSize.Zero) }
    var preview by remember(day) { mutableStateOf<DemoEvent?>(null) }
    var previewMode by remember(day) { mutableIntStateOf(0) }
    var dragging by remember(day) { mutableStateOf(false) }
    var pendingCommit by remember(day) { mutableStateOf<DemoEvent?>(null) }
    // Keep one painted event until the durable save publishes its matching ID/time.
    // A rejected save clears the preview when the store leaves its busy state.
    LaunchedEffect(day, events, saving, pendingCommit) {
        pendingCommit?.let { committed ->
            if (events.any { it.id == committed.id && it.start == committed.start && it.end == committed.end } || !saving) {
                pendingCommit = null
                preview = null
            }
        }
    }
    val displayed = preview?.let { p ->
        if (events.any { it.id == p.id }) events.map { if (it.id == p.id) p else it } else events + p
    } ?: events
    val selected = displayed.firstOrNull { it.id == selectedId }
    val handleInk = Primary
    val handleFill = MaterialTheme.colorScheme.primaryContainer
    Box(Modifier.fillMaxHeight().testTag("time-matrix").onSizeChanged { matrixSize = it }
        .pointerInput(day, matrixSize, events, activeCategory?.id, child, density) {
            awaitEachGesture {
                var handedToSave = false
                try {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (pendingCommit != null) return@awaitEachGesture
                    if (matrixSize.width <= 27 * density || matrixSize.height <= 0) return@awaitEachGesture
                    val selectedEvent = events.firstOrNull { it.id == latestSelected }
                    val endpoints = selectedEvent?.let { e -> listOf(false, true).filter { if (it) e.end <= 1440 else e.start >= 0 }.map { end ->
                        val p = endpointPosition(e, end, matrixSize, density)
                        val dx = down.position.x - p.x; val dy = down.position.y - p.y
                        (if (end) 3 else 2) to (dx * dx + dy * dy)
                    }.filter { it.second <= 24f * 24f * density * density }.minByOrNull { it.second } }
                    val hitMinute = exactMatrixMinute(down.position, matrixSize, density)
                    val region = hitMinute?.let { m -> matrixRegions(events, (m / 180).toInt().coerceIn(0, 7))
                        .firstOrNull { m >= it.start && m < it.end } }
                    // A lower selected member owns a subsequent drag, but a tap still cycles.
                    val hit = region?.ids?.let { ids -> events.firstOrNull {
                        it.id == (latestSelected?.takeIf { id -> id in ids } ?: ids.firstOrNull())
                    } }
                    val original = if (endpoints != null) selectedEvent else hit
                    val mode = endpoints?.first ?: if (hit != null) 1 else 0
                    if (hitMinute == null && endpoints == null) {
                        latestSetCycle(null); return@awaitEachGesture
                    }
                    val anchor = gestureMinute(down.position, matrixSize, density)
                    var current = down
                    var tap = false
                    var aborted = false
                    var drag = false
                    // Decide taps on release, so cancelling or adding a finger never advances
                    // the cycle. Endpoint taps also cycle instead of trapping the selected layer.
                    val completedBeforeTimeout = withTimeoutOrNull(
                        if (mode == 1) viewConfiguration.longPressTimeoutMillis else Long.MAX_VALUE
                    ) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) {
                                aborted = true; break
                            }
                            current = change
                            val distance = (change.position - down.position).getDistance()
                            if (!change.pressed) { tap = distance <= viewConfiguration.touchSlop; break }
                            if (distance > viewConfiguration.touchSlop) {
                                if (mode == 1) aborted = true else drag = true
                                break
                            }
                        }
                        true
                    }
                    if (tap) {
                        if (region != null) {
                            latestSelect(nextRegionId(region, latestCycle, latestSelected))
                            latestSetCycle(region.takeIf { it.ids.size > 1 })
                        } else latestSetCycle(null)
                        current.consume()
                        return@awaitEachGesture
                    }
                    if (aborted) {
                        latestSetCycle(null)
                        // Preserve the old short-body-drag selection, without edits/history.
                        if (current.pressed && (current.position - down.position).getDistance() > viewConfiguration.touchSlop)
                            latestSelect(original?.id)
                        return@awaitEachGesture
                    }
                    if (completedBeforeTimeout == null && mode == 1) drag = true
                    if (!drag || (mode == 0 && activeCategory == null)) return@awaitEachGesture
                    latestSetCycle(null)
                    latestSelect(original?.id)
                    val newId = UUID.randomUUID().toString()
                    fun update(position: Offset) {
                        val minute = gestureMinute(position, matrixSize, density)
                        preview = if (original != null) transformedEvent(original, mode, anchor, minute)
                            else DemoEvent(minOf(anchor, minute).coerceAtMost(1435),
                                (maxOf(anchor, minute) + 5).coerceAtMost(1440), activeCategory!!.id, child, newId)
                    }
                    previewMode = mode
                    dragging = true
                    current.consume()
                    update(current.position)
                    var completed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.id != down.id && it.pressed }) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (!change.pressed) { completed = true; change.consume(); break }
                        update(change.position)
                        change.consume()
                    }
                    val result = preview
                    if (completed && result != null) {
                        if (original == null) {
                            pendingCommit = result
                            handedToSave = true
                            latestCreate(result)
                        } else if (result.start != original.start || result.end != original.end) {
                            pendingCommit = result
                            handedToSave = true
                            latestEdit(original.id, result.start, result.end)
                        }
                    }
                } finally {
                    dragging = false
                    if (!handedToSave) preview = null
                }
            }
        }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(8) { TimeMatrixRow(it, displayed, categories, selectedId, Modifier.weight(1f)) }
        }
        if (selected != null) Canvas(Modifier.matchParentSize()) {
            listOf(false, true).filter { if (it) selected.end <= 1440 else selected.start >= 0 }.forEach { end ->
                val p = endpointPosition(selected, end, matrixSize, density)
                val x = p.x.coerceIn(27.dp.toPx() + 3.dp.toPx(), size.width - 3.dp.toPx())
                drawLine(handleFill, Offset(x, p.y - 4.dp.toPx()), Offset(x, p.y + 4.dp.toPx()),
                    6.dp.toPx(), StrokeCap.Round)
                drawLine(handleInk, Offset(x, p.y - 4.dp.toPx()), Offset(x, p.y + 4.dp.toPx()),
                    2.dp.toPx(), StrokeCap.Round)
            }
        }
        // A visual-only sibling: no pointer input, click handling or layout reservation.
        preview?.takeIf { dragging }?.let { event ->
            val action = when (previewMode) { 0 -> "新建"; 1 -> "移动"; else -> "调整" }
            Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(50)).background(Color(0xFFF4F1E8))
                .padding(horizontal = 12.dp, vertical = 6.dp).testTag("drag-preview")) {
                Text("$action ${clockMinute(event.start)}–${clockMinute(event.end)} · ${durationLabel(event.end - event.start)}",
                    color = Color(0xFF252C30), fontSize = 11.sp, lineHeight = 16.sp,
                    textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

internal fun continuationEdges(eventStart: Int, eventEnd: Int, row: Int): Pair<Boolean, Boolean> {
    val start = row * 180
    val end = start + 180
    if (row !in 0..7 || eventStart >= eventEnd || eventStart >= end || eventEnd <= start)
        return false to false
    return (eventStart < start) to (eventEnd > end)
}

internal fun overlapStripeColor(first: Color, second: Color): Color {
    if (first != second) return second
    val webStripe = Color(0xFFA8C4B9)
    // v0.4's pale green; ensure it remains visible on custom colors near that shade.
    return if (contrastRatio(first, webStripe) >= 1.4f) webStripe
    else lerp(first, if (first.luminance() > .179f) Color.Black else Color.White, .38f)
}

@Composable
private fun TimeMatrixRow(row: Int, events: List<DemoEvent>, categories: List<Category>, selectedId: String?, modifier: Modifier = Modifier) {
    val gridInk = Secondary
    val eventOpacity = LocalAppearance.current.eventOpacity
    val selectionInk = Primary
    val fallback = TrackBg
    val textShadow = with(LocalDensity.current) {
        androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .70f), Offset(0f, 1.dp.toPx()), 2.dp.toPx())
    }
    val start = row * 180
    val end = start + 180
    val regions = remember(events, row) { matrixRegions(events, row) }
    fun color(event: DemoEvent): Color {
        val category = categories.firstOrNull { it.id == event.categoryId }
        return category?.children?.firstOrNull { it.id == event.child }?.color ?: category?.color ?: fallback
    }
    val colors = events.associate { it.id to color(it) }
    val shape = RoundedCornerShape(BricksDimensions.medium)
    val radius = BricksDimensions.medium
    fun segmentShape(event: DemoEvent): RoundedCornerShape {
        val edges = continuationEdges(event.start, event.end, row)
        val left = if (edges.first) 4.dp else radius
        val right = if (edges.second) 4.dp else radius
        return RoundedCornerShape(topStart = left, bottomStart = left, topEnd = right, bottomEnd = right)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(String.format(Locale.ROOT, "%02d", row * 3), color = Secondary,
            fontSize = 12.sp, modifier = Modifier.width(27.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(TrackBg, shape)) {
            val totalWidth = maxWidth
            Canvas(Modifier.fillMaxSize()) {
                for (i in 1 until 12) {
                    val x = size.width * i / 12f
                    drawLine(if (i % 4 == 0) gridInk.copy(alpha = .30f) else gridInk.copy(alpha = .08f),
                        Offset(x, 0f), Offset(x, size.height), if (i % 4 == 0) 1.5f else 1f)
                }
            }
            // Composite all category-color fills and overlap stripes once at the chosen opacity.
            // Labels, continuation dots, selection outlines and endpoint handles remain fully opaque.
            Box(Modifier.fillMaxSize().graphicsLayer(alpha = eventOpacity)) {
            // First paint every capsule in its stable order, with stripes clipped to both
            // the exact overlap interval and the top capsule's rounded outline.
            events.forEach { event ->
                val a = maxOf(event.start, start); val b = minOf(event.end, end)
                if (a < b) Box(Modifier.offset(x = totalWidth * ((a - start) / 180f))
                    .width(totalWidth * ((b - a) / 180f)).fillMaxHeight().clip(segmentShape(event)).background(colors.getValue(event.id))) {
                    Canvas(Modifier.fillMaxSize()) {
                        regions.filter { it.ids.size > 1 && it.ids.first() == event.id }.forEach { region ->
                            val first = colors.getValue(event.id)
                            val second = region.ids.asReversed().firstOrNull { colors[it] != first }
                                ?.let { colors.getValue(it) } ?: first
                            val stripe = overlapStripeColor(first, second)
                            val left = size.width * (region.start - a) / (b - a)
                            val right = size.width * (region.end - a) / (b - a)
                            clipRect(left, 0f, right, size.height) {
                                // Keep original category fills; same-color overlaps receive the v0.4 contrast stripe.
                                val band = 5.dp.toPx()
                                val phase = totalWidth.toPx() * (a - start) / 180f
                                var x = -size.height - ((phase - size.height) % (band * 2) + band * 2) % (band * 2)
                                while (x < size.width) {
                                    val path = Path().apply {
                                        moveTo(x, size.height); lineTo(x + size.height, 0f)
                                        lineTo(x + size.height + band, 0f); lineTo(x + band, size.height); close()
                                    }
                                    drawPath(path, stripe)
                                    x += band * 2
                                }
                            }
                        }
                    }
                }
            }
            } // Single opacity layer: prevents stacked translucency in overlapping regions.
            // One mark per visible row edge avoids opacity stacking for overlapping events.
            // Strict comparisons exclude true endpoints; clipping matches the capsule shape.
            val leftContinuation = events.filter { continuationEdges(it.start, it.end, row).first }.maxByOrNull { it.end }
            val rightContinuation = events.filter { continuationEdges(it.start, it.end, row).second }.minByOrNull { it.start }
            listOfNotNull(leftContinuation?.let { it to true }, rightContinuation?.let { it to false }).forEach { (event, left) ->
                val a = maxOf(event.start, start); val b = minOf(event.end, end)
                Canvas(Modifier.offset(x = totalWidth * ((a - start) / 180f))
                    .width(totalWidth * ((b - a) / 180f)).fillMaxHeight().clip(segmentShape(event))) {
                    val dot = minOf(1.dp.toPx(), size.width / 4, size.height / 8)
                    val inset = minOf(1.2.dp.toPx(), size.width / 2)
                    val x = if (left) inset else size.width - inset
                    val margin = minOf(4.dp.toPx(), size.height / 4)
                    val count = maxOf(2, ((size.height - 2 * margin) / 4.dp.toPx()).toInt() + 1)
                    val step = (size.height - 2 * margin) / (count - 1)
                    for (i in 0 until count) drawCircle(Color(0xFFDBE4DF).copy(alpha = .42f), dot,
                        Offset(x, margin + i * step))
                }
            }
            // Labels are a separate final layer, with no opaque backing or stripe cutout.
            // Merge adjacent regions owned by the same label to avoid duplicate fragments.
            val labels = mutableListOf<OverlapRegion>()
            regions.forEach { r ->
                val owner = selectedId?.takeIf { it in r.ids } ?: r.ids.firstOrNull()
                if (owner != null) {
                    val last = labels.lastOrNull()
                    if (last != null && last.ids.first() == owner && last.end == r.start)
                        labels[labels.lastIndex] = last.copy(end = r.end)
                    else labels += r.copy(ids = listOf(owner))
                }
            }
            labels.forEach { region ->
                val event = events.first { it.id == region.ids.first() }
                val category = categories.firstOrNull { it.id == event.categoryId }
                val subcategory = category?.children?.firstOrNull { it.id == event.child }
                val title = (category?.name ?: "未知分类") +
                    if (event.child.isEmpty()) "" else "·${subcategory?.name ?: "未知子分类"}"
                val widthFraction = (region.end - region.start) / 180f
                if (widthFraction >= .24f) Box(Modifier.offset(x = totalWidth * ((region.start - start) / 180f))
                    .width(totalWidth * widthFraction).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(title, color = Color.White, style = TextStyle(shadow = textShadow), fontSize = 11.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 5.dp))
                }
            }
            events.firstOrNull { it.id == selectedId }?.let { event ->
                val a = maxOf(event.start, start); val b = minOf(event.end, end)
                if (a < b) Canvas(Modifier.offset(x = totalWidth * ((a - start) / 180f))
                    .width(totalWidth * ((b - a) / 180f)).fillMaxHeight()) {
                    val inset = minOf(1.dp.toPx(), size.width / 4)
                    val edges = continuationEdges(event.start, event.end, row)
                    fun corner(continued: Boolean): CornerRadius = CornerRadius(
                        (minOf((if (continued) 4.dp else radius).toPx(), size.width / 2, size.height / 2) - inset).coerceAtLeast(0f))
                    val left = corner(edges.first); val right = corner(edges.second)
                    val outline = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        left = inset, top = inset, right = size.width - inset, bottom = size.height - inset,
                        topLeftCornerRadius = left, bottomLeftCornerRadius = left,
                        topRightCornerRadius = right, bottomRightCornerRadius = right)) }
                    drawPath(outline, selectionInk, style = Stroke(2 * inset))
                }
            }
        }
    }
}

@Composable
private fun CompactBottomNavigation(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).background(NavBg)
        .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("规划", "日程", "设置").forEachIndexed { index, name ->
            Box(Modifier.weight(1f).fillMaxHeight().testTag("navigation-$index").clip(RoundedCornerShape(BricksDimensions.medium))
                .background(if (selected == index) Accent else NavBg)
                .selectable(selected = selected == index, role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center) {
                Text(name, color = if (selected == index) Primary else Secondary, fontSize = 14.sp,
                    fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PlaceholderPage(title: String, description: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Primary, fontSize = 23.sp)
        Spacer(Modifier.height(10.dp))
        Text(description, color = Secondary, fontSize = 13.sp)
    }
}

private data class DisplaySettings(
    val mainRows: Int = 2, val childRows: Int = 1, val darkTheme: Boolean = true,
    val themeMode: String = if (darkTheme) "dark" else "light",
    val themeHex: String = "527F93", val fontScale: Float = 1f,
    val spacingScale: Float = 1f, val cornerDp: Float = 16f,
    val eventOpacity: Float = 1f, val recentColors: List<String> = emptyList()
)

private fun DisplaySettings.usesDarkTheme(systemDark: Boolean): Boolean = when (themeMode) {
    "system" -> systemDark
    "light" -> false
    else -> true
}

private fun parseThemeHex(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    return if (hex.matches(Regex("[0-9a-fA-F]{6}"))) (0xFF000000L or hex.toLong(16)).toInt() else null
}

// One immutable snapshot keeps categories, settings and all dated event references consistent.

private data class AppData(
    val categories: List<Category> = initialCategories,
    val createdEvents: Map<Long, List<DemoEvent>> = emptyMap(),
    val settings: DisplaySettings = DisplaySettings(),
    val templates: List<ScheduleTemplate> = emptyList(),
    val defaultHistory: List<DefaultTemplateVersion> = emptyList(),
    val repeatRules: List<RepeatRule> = emptyList(),
    val repeatExceptions: List<RepeatException> = emptyList(),
    val categoryPauses: List<CategoryPause> = emptyList()
)

private fun AppData.withRecentColor(hex: String): AppData {
    val normalized = hex.trim().removePrefix("#").uppercase(Locale.ROOT)
    require(parseThemeHex(normalized) != null) { "颜色无效" }
    return copy(settings = settings.copy(recentColors = (listOf(normalized) + settings.recentColors).distinct().take(8)))
}

private fun AppData.references(parentId: String, childId: String? = null): Int =
    (createdEvents.values.flatten() + templates.flatMap { it.events } + defaultHistory.flatMap { it.events } +
        repeatRules.flatMap { it.versions }.mapNotNull { it.event } + repeatExceptions.mapNotNull { it.replacement }).count {
        it.categoryId == parentId && (childId == null || it.child == childId)
    }

private fun validName(name: String): Boolean =
    name.isNotBlank() && name == name.trim() && name.length <= 24 && name.none { it.isISOControl() }

private fun AppData.checkName(parentId: String?, id: String?, name: String) {
    require(validName(name)) { "名称需为 1–24 个字符，不能含换行" }
    val siblings = if (parentId == null) categories.map { it.id to it.name }
        else categories.first { it.id == parentId }.children.map { it.id to it.name }
    require(siblings.none { it.first != id && it.second.equals(name, ignoreCase = true) }) {
        "同级已有这个名称（含已归档分类），请换一个名称或恢复原分类"
    }
}

private fun AppData.editCategory(parentId: String?, id: String?, name: String, color: Color): AppData {
    checkName(parentId, id, name)
    require(id == null || if (parentId == null) categories.any { it.id == id }
        else categories.first { it.id == parentId }.children.any { it.id == id }) { "分类已不存在" }
    val newId = id ?: UUID.randomUUID().toString()
    return if (parentId == null) {
        copy(categories = if (id == null) categories + Category(newId, name, color, emptyList())
            else categories.map { if (it.id == id) it.copy(name = name, color = color) else it })
    } else copy(categories = categories.map { category ->
        if (category.id != parentId) category else category.copy(children =
            if (id == null) category.children + Subcategory(newId, name, color)
            else category.children.map { if (it.id == id) it.copy(name = name, color = color) else it })
    })
}

private fun AppData.archiveCategory(parentId: String?, id: String, archived: Boolean): AppData =
    copy(categories = categories.map { category ->
        if (parentId == null) {
            if (category.id == id) category.copy(archived = archived) else category
        } else if (category.id == parentId) category.copy(children = category.children.map {
            if (it.id == id) it.copy(archived = archived) else it
        }) else category
    })

private fun <T> movedItem(items: List<T>, index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in items.indices || target !in items.indices) return items
    return items.toMutableList().apply { add(target, removeAt(index)) }.toList()
}

private fun AppData.moveCategory(parentId: String?, id: String, delta: Int): AppData =
    if (parentId == null) copy(categories = movedItem(categories, categories.indexOfFirst { it.id == id }, delta))
    else copy(categories = categories.map { category ->
        if (category.id != parentId) category else category.copy(children =
            movedItem(category.children, category.children.indexOfFirst { it.id == id }, delta))
    })

// Reorder the visible subset while preserving archived/hidden slots and all IDs.
private fun AppData.reorderCategories(parentId: String?, orderedIds: List<String>): AppData {
    val current = if (parentId == null) categories.map { it.id }
        else categories.first { it.id == parentId }.children.map { it.id }
    require(orderedIds.distinct().size == orderedIds.size && current.containsAll(orderedIds)) { "分类顺序无效" }
    val selected = orderedIds.toSet()
    var position = 0
    val result = current.map { if (it in selected) orderedIds[position++] else it }
    return if (parentId == null) copy(categories = result.map { id -> categories.first { it.id == id } })
        else copy(categories = categories.map { category ->
            if (category.id != parentId) category else category.copy(children = result.map { id -> category.children.first { it.id == id } })
        })
}

// A reversible availability interval preserves past dates when a category is restored later.
private data class CategoryPause(val parentId: String, val childId: String? = null, val fromDay: Long, val untilDay: Long? = null)
private fun AppData.categoryAvailable(event: DemoEvent, day: Long): Boolean = categoryPauses.none {
    it.parentId == event.categoryId && (it.childId == null || it.childId == event.child) &&
        day >= it.fromDay && (it.untilDay == null || day < it.untilDay)
}
private fun AppData.setCategoryAvailability(parentId: String?, id: String, archived: Boolean, day: Long): AppData {
    LocalDate.ofEpochDay(day)
    val parent = parentId ?: id; val child = if (parentId == null) null else id
    val category = categories.firstOrNull { it.id == parent }; require(category != null) { "分类已不存在" }
    val current = if (child == null) category.archived else category.children.firstOrNull { it.id == child }?.archived
    require(current != null) { "子分类已不存在" }
    if (current == archived) return this
    val previous = categoryPauses.filter { it.parentId == parent && it.childId == child }
    require(previous.all { it.fromDay <= day && (it.untilDay == null || it.untilDay <= day) }) { "系统日期早于最近归档变更" }
    val pauses = if (archived) categoryPauses + CategoryPause(parent, child, day)
        else categoryPauses.map { if (it.parentId == parent && it.childId == child && it.untilDay == null) it.copy(untilDay = day) else it }
    return archiveCategory(parentId, id, archived).copy(categoryPauses = pauses.filter { it.untilDay != it.fromDay }).also { it.validate() }
}

// Explicit destructive operation: purge only the selected category, including historical sources.
// Keep null-event version boundaries so an earlier, different category never fills a deleted interval.
private fun AppData.purgeCategory(parentId: String?, id: String): AppData {
    val parent = parentId ?: id; val child = if (parentId == null) null else id
    require(categories.any { it.id == parent && (child == null || it.children.any { c -> c.id == child }) }) { "分类已不存在" }
    fun matches(event: DemoEvent?) = event != null && event.categoryId == parent && (child == null || event.child == child)
    fun clean(events: List<DemoEvent>) = events.filterNot { matches(it) }
    val exceptions = repeatExceptions.map { if (matches(it.replacement)) it.copy(replacement = null) else it }
    val rules = repeatRules.map { rule -> rule.copy(versions = rule.versions.map { if (matches(it.event)) it.copy(event = null) else it }) }
        .filter { rule -> rule.versions.any { it.event != null } || exceptions.any { it.ruleId == rule.id && it.replacement != null } }
    return copy(
        categories = if (child == null) categories.filterNot { it.id == parent } else categories.map { c ->
            if (c.id == parent) c.copy(children = c.children.filterNot { it.id == child }) else c },
        createdEvents = createdEvents.mapValues { clean(it.value) },
        templates = templates.map { it.copy(events = clean(it.events)) },
        defaultHistory = defaultHistory.map { it.copy(events = clean(it.events)) },
        repeatRules = rules, repeatExceptions = exceptions.filter { ex -> rules.any { it.id == ex.ruleId } },
        categoryPauses = categoryPauses.filterNot { it.parentId == parent && (child == null || it.childId == child) }
    ).also { it.validate() }
}

private fun AppData.deleteCategory(parentId: String?, id: String): AppData {
    require(references(parentId ?: id, if (parentId == null) null else id) == 0) {
        "该分类被日程、模板、重复规则或历史版本引用，只能归档"
    }
    if (parentId == null) {
        require(categories.first { it.id == id }.children.isEmpty()) { "请先处理该主分类下的子分类，或直接归档主分类" }
        return copy(categories = categories.filterNot { it.id == id })
    }
    return copy(categories = categories.map {
        if (it.id == parentId) it.copy(children = it.children.filterNot { sub -> sub.id == id }) else it
    })
}

// A template owns item IDs. Dates own separate instance IDs, including read-only previews.
private data class ScheduleTemplate(
    val id: String, val name: String, val events: List<DemoEvent>, val revision: Int = 1, val archived: Boolean = false
)

// D12: a default change is effective today; earlier calendar dates keep their version.
// Store one immutable source per change, never one copy per viewed/unmodified day.
private data class DefaultTemplateVersion(
    val fromDay: Long, val templateId: String?, val name: String,
    val revision: Int, val events: List<DemoEvent>
)

private fun validTemplateName(name: String): Boolean =
    name.isNotBlank() && name == name.trim() && name.none { it.isISOControl() }

private fun AppData.defaultOn(day: Long): DefaultTemplateVersion? =
    defaultHistory.lastOrNull { it.fromDay <= day }

// Rule revisions are date-bounded; identity never depends on time, category or revision.
private data class RepeatVersion(
    val fromDay: Long, val revision: Int, val name: String, val event: DemoEvent?,
    val startDay: Long, val endDay: Long? = null,
    val weekdays: Set<Int> = (1..7).toSet(), val status: String = "active"
)
private data class RepeatRule(val id: String, val versions: List<RepeatVersion>)
private data class RepeatException(val ruleId: String, val day: Long, val replacement: DemoEvent? = null)
private fun occurrenceId(ruleId: String, day: Long): String =
    "repeat-" + UUID.nameUUIDFromBytes(JSONArray().put(ruleId).put(day).toString().toByteArray(Charsets.UTF_8))
private fun RepeatRule.versionOn(day: Long): RepeatVersion? = versions.lastOrNull { it.fromDay <= day }
private fun RepeatVersion.matches(day: Long): Boolean =
    day >= startDay && (endDay == null || day <= endDay) && LocalDate.ofEpochDay(day).dayOfWeek.value in weekdays

private fun AppData.repeatEventsOn(day: Long): List<DemoEvent> = repeatRules.mapNotNull { rule ->
    val version = rule.versionOn(day) ?: return@mapNotNull null
    // Pausing/archiving/deleting suspends all occurrences, including overrides, in that interval.
    if (version.status != "active") return@mapNotNull null
    val exception = repeatExceptions.firstOrNull { it.ruleId == rule.id && it.day == day }
    if (exception != null) exception.replacement?.copy(id = occurrenceId(rule.id, day))
    else if (version.matches(day)) version.event?.copy(id = occurrenceId(rule.id, day)) else null
}
// Resolve complete occurrences first. A day projection retains full relative endpoints and ID.
private fun AppData.anchoredEventsOn(day: Long): List<DemoEvent> =
    (baseEventsOn(day) + repeatEventsOn(day)).filter { categoryAvailable(it, day + it.dayOffset) }

private fun AppData.eventsOn(day: Long): List<DemoEvent> {
    LocalDate.ofEpochDay(day)
    val longest = (createdEvents.values.flatten() + defaultHistory.flatMap { it.events } +
        repeatRules.flatMap { it.versions.mapNotNull { v -> v.event } } + repeatExceptions.mapNotNull { it.replacement })
        .maxOfOrNull { (it.end - 1) / 1440 } ?: 0
    val anchors = (day - longest..day).toMutableSet()
    createdEvents.forEach { (anchor, events) ->
        if (events.any { it.dayOffset != 0 && day - (anchor + it.dayOffset) in 0L..((it.end - 1) / 1440).toLong() }) anchors += anchor
    }
    repeatExceptions.forEach { ex ->
        val offset = ex.replacement?.dayOffset ?: 0
        if (offset != 0 && day - (ex.day + offset) in 0L..(((ex.replacement?.end ?: 1) - 1) / 1440).toLong()) anchors += ex.day
    }
    val events = anchors.flatMap { anchor -> anchoredEventsOn(anchor).mapNotNull { event ->
        val displacement = (anchor + event.dayOffset - day) * 1440L
        val start = event.start + displacement; val end = event.end + displacement
        if (start < 1440 && end > 0) event.copy(start = start.toInt(), end = end.toInt(), originDay = anchor) else null
    } }
    require(events.map { it.id }.distinct().size == events.size) { "生效事件ID冲突" }
    return events
}

private fun DemoEvent.storedFromView(viewDay: Long, anchor: Long): DemoEvent {
    val shift = Math.floorDiv(start, 1440)
    val normalizedEnd = end - shift * 1440
    require(normalizedEnd in 1..MAX_EVENT_MINUTES && normalizedEnd > Math.floorMod(start, 1440)) { "结束须晚于开始，最多占用366个日期" }
    val offset = Math.toIntExact(viewDay - anchor + shift)
    require(offset in -366000..366000) { "移动日期超出支持范围" }
    return copy(start = Math.floorMod(start, 1440), end = normalizedEnd, dayOffset = offset, originDay = null)
}

private fun DemoEvent.isCrossDay(): Boolean = Math.floorDiv(start, 1440) != Math.floorDiv(end - 1, 1440)
private fun DemoEvent.timeLabel(): String =
    clockMinute(start) + "–" + clockMinute(end) + " · 共" + durationLabel(end - start)

private fun AppData.ruleForOccurrence(day: Long, id: String): RepeatRule? =
    repeatRules.firstOrNull { occurrenceId(it.id, day) == id }

private fun AppData.checkRepeatEvent(event: DemoEvent, old: DemoEvent? = null) {
    require(event.start in 0..1439 && event.end in 1..MAX_EVENT_MINUTES && event.start < event.end) { "结束须晚于开始，最多占用366个日期" }
    require(event.originDay == null && event.dayOffset in -366000..366000) { "事件日期偏移无效" }
    val category = categories.firstOrNull { it.id == event.categoryId }
    require(category != null && (event.child.isEmpty() || category.children.any { it.id == event.child })) { "分类引用不完整" }
    if (old == null || event.categoryId != old.categoryId || event.child != old.child) {
        require(!category.archived && (event.child.isEmpty() || category.children.any { it.id == event.child && !it.archived })) { "所选分类已归档，请选可用分类" }
    }
}
private fun AppData.saveRepeatRule(id: String?, version: RepeatVersion): AppData {
    val old = id?.let { key -> repeatRules.firstOrNull { it.id == key } }
    require(id == null || old != null) { "重复规则已不存在" }
    require(old?.versions?.last()?.status != "deleted") { "已删除规则不能修改，可撤销删除" }
    require(old == null || version.fromDay >= old.versions.last().fromDay) { "生效日不能早于最近规则变更；请先撤销最近变更" }
    require(version.event != null || old?.versions?.last()?.event == null && old != null) { "新建规则必须包含事件" }
    version.event?.let { checkRepeatEvent(it, old?.versions?.last()?.event) }
    val key = old?.id ?: UUID.randomUUID().toString()
    val updated = version.copy(revision = Math.addExact(old?.versions?.last()?.revision ?: 0, 1),
        event = version.event?.copy(id = "rule-item-$key"))
    val rule = RepeatRule(key, old?.versions.orEmpty().filter { it.fromDay < version.fromDay } + updated)
    return copy(repeatRules = if (old == null) repeatRules + rule else repeatRules.map { if (it.id == key) rule else it }).also { it.validate() }
}
private fun AppData.setRepeatStatus(id: String, fromDay: Long, status: String): AppData {
    require(status in listOf("active", "paused", "archived", "deleted")) { "重复规则状态无效" }
    val rule = repeatRules.firstOrNull { it.id == id }
    require(rule != null) { "重复规则已不存在" }
    return saveRepeatRule(id, rule.versions.last().copy(fromDay = fromDay, status = status))
}
private fun AppData.setRepeatException(day: Long, id: String, replacement: DemoEvent?): AppData {
    val rule = ruleForOccurrence(day, id)
    require(rule != null) { "重复规则已不存在" }
    val old = repeatEventsOn(day).firstOrNull { it.id == id }
    require(old != null) { "该次重复事件已不存在" }
    replacement?.let { checkRepeatEvent(it, old) }
    val next = RepeatException(rule.id, day, replacement?.copy(id = id))
    // Freeze ONLY the base source, never absorb recurring instances into a day snapshot.
    return copy(createdEvents = createdEvents + (day to baseEventsOn(day)),
        repeatExceptions = repeatExceptions.filterNot { it.ruleId == rule.id && it.day == day } + next)
}
private fun AppData.restoreRepeatException(ruleId: String, day: Long): AppData =
    copy(repeatExceptions = repeatExceptions.filterNot { it.ruleId == ruleId && it.day == day })

private fun AppData.validateRepeats() {
    require(repeatRules.map { it.id }.distinct().size == repeatRules.size) { "重复规则ID冲突" }
    repeatRules.forEach { rule ->
        require(rule.id.isNotBlank() && rule.versions.isNotEmpty()) { "重复规则无效" }
        require(rule.versions.zipWithNext().all { (a, b) -> a.fromDay < b.fromDay && a.revision < b.revision }) { "重复规则版本顺序无效" }
        rule.versions.forEach { v ->
            LocalDate.ofEpochDay(v.fromDay); LocalDate.ofEpochDay(v.startDay)
            v.endDay?.let { LocalDate.ofEpochDay(it) }
            require(validName(v.name) && v.revision > 0 && (v.endDay == null || v.endDay >= v.startDay)) { "重复规则名称或日期范围无效" }
            require(v.weekdays.isNotEmpty() && v.weekdays.all { it in 1..7 }) { "请选择有效星期" }
            require(v.status in listOf("active", "paused", "archived", "deleted")) { "重复规则状态无效" }
            v.event?.let { checkRepeatEvent(it, it); require(it.dayOffset == 0) { "重复源日期偏移必须为零" } }
        }
        require(rule.versions.dropLast(1).none { it.status == "deleted" }) { "删除后不能新增规则版本" }
    }
    require(repeatExceptions.map { it.ruleId to it.day }.distinct().size == repeatExceptions.size) { "单次例外重复" }
    repeatExceptions.forEach { ex ->
        LocalDate.ofEpochDay(ex.day)
        require(repeatRules.any { it.id == ex.ruleId && it.versions.first().fromDay <= ex.day }) { "单次例外规则引用无效" }
        ex.replacement?.let { require(it.id == occurrenceId(ex.ruleId, ex.day)) { "单次例外ID无效" }; checkRepeatEvent(it, it) }
    }
    // Reserve the instance namespace; reject corrupt imports rather than merge identities.
    require((createdEvents.values.flatten() + templates.flatMap { it.events } + defaultHistory.flatMap { it.events })
        .none { it.id.startsWith("repeat-") }) { "基础事件不能使用重复实例ID" }
}
private fun repeatsToJson(rules: List<RepeatRule>): JSONArray = JSONArray().also { array ->
    rules.forEach { rule ->
        val versions = JSONArray()
        rule.versions.forEach { v ->
            versions.put(JSONObject().put("fromDay", v.fromDay).put("revision", v.revision).put("name", v.name)
                .put("removed", v.event == null).put("events", eventsToJson(listOfNotNull(v.event))).put("startDay", v.startDay)
                .put("endDay", v.endDay ?: JSONObject.NULL).put("weekdays", JSONArray(v.weekdays.sorted())).put("status", v.status))
        }
        array.put(JSONObject().put("id", rule.id).put("versions", versions))
    }
}
private fun repeatsFromJson(array: JSONArray, allowRemoved: Boolean = false): List<RepeatRule> = List(array.length()) { i ->
    val item = array.getJSONObject(i); val versions = item.getJSONArray("versions")
    RepeatRule(item.getString("id"), List(versions.length()) { j ->
        val v = versions.getJSONObject(j); val weekdays = v.getJSONArray("weekdays")
        val days = List(weekdays.length()) { weekdays.getInt(it) }
        require(days.distinct().size == days.size && v.has("endDay")) { "重复规则字段无效" }
        val events = eventsFromJson(v.getJSONArray("events"))
        val removed = allowRemoved && v.getBoolean("removed")
        require(if (removed) events.isEmpty() else events.size == 1) { "规则事件无效" }
        RepeatVersion(v.getLong("fromDay"), v.getInt("revision"), v.getString("name"), events.singleOrNull(),
            v.getLong("startDay"), if (v.isNull("endDay")) null else v.getLong("endDay"), days.toSet(), v.getString("status"))
    })
}
private fun exceptionsToJson(exceptions: List<RepeatException>): JSONArray = JSONArray().also { array ->
    exceptions.forEach { ex -> array.put(JSONObject().put("ruleId", ex.ruleId).put("day", ex.day)
        .put("replacement", ex.replacement?.let { eventsToJson(listOf(it)) } ?: JSONObject.NULL)) }
}
private fun exceptionsFromJson(array: JSONArray): List<RepeatException> = List(array.length()) { i ->
    val item = array.getJSONObject(i); require(item.has("replacement")) { "单次例外字段缺失" }
    val events = if (item.isNull("replacement")) null else eventsFromJson(item.getJSONArray("replacement"))
    require(events == null || events.size == 1) { "单次例外事件无效" }
    RepeatException(item.getString("ruleId"), item.getLong("day"), events?.single())
}

private fun AppData.baseEventsOn(day: Long): List<DemoEvent> {
    // Presence, not list size, distinguishes an independent (possibly empty) day.
    createdEvents[day]?.let { return it }
    val source = defaultOn(day) ?: return emptyList()
    return source.events.map { item ->
        val key = JSONArray().put(day).put(source.templateId).put(source.revision).put(item.id).toString()
        item.copy(id = "inherited-" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)),
            scheduleIdentity = templateScheduleIdentity(source.templateId, item.id))
    }
}

// Independent of revision/time; the JSON identity is also used by ZIP readers.
private fun templateScheduleIdentity(templateId: String?, itemId: String): String =
    ScheduleContract.templateIdentity(requireNotNull(templateId), itemId)

// v3-v5 dated snapshots may contain inherited native IDs. Resolve them while their history
// is still available, then persist the identity on the next normal save (queries never save).
private fun AppData.withScheduleIdentities(): AppData = copy(createdEvents = createdEvents.mapValues { (day, events) ->
    if (events.none { it.scheduleIdentity == null && it.id.startsWith("inherited-") }) return@mapValues events
    val known = defaultHistory.flatMap { source -> source.events.map { item ->
        val old = "inherited-" + UUID.nameUUIDFromBytes(JSONArray().put(day).put(source.templateId)
            .put(source.revision).put(item.id).toString().toByteArray(Charsets.UTF_8))
        old to templateScheduleIdentity(source.templateId, item.id)
    } }.toMap()
    events.map { event -> if (event.scheduleIdentity != null) event else
        event.copy(scheduleIdentity = known[event.id]) }
})

private fun AppData.daySourceLabel(day: Long): String = when {
    day in createdEvents -> if (createdEvents.getValue(day).isEmpty()) "本日独立空白" else "本日独立安排"
    defaultOn(day)?.templateId != null -> "继承 · ${defaultOn(day)!!.name}"
    else -> "默认空白"
}

private fun AppData.modifyEvent(day: Long, id: String, transform: (DemoEvent) -> DemoEvent): AppData {
    val view = eventsOn(day).firstOrNull { it.id == id } ?: error("事件已不存在")
    val anchor = requireNotNull(view.originDay)
    val next = transform(view).storedFromView(day, anchor)
    return modifyAnchoredEvent(anchor, id) { next }
}
private fun AppData.modifyAnchoredEvent(day: Long, id: String, transform: (DemoEvent) -> DemoEvent): AppData {
    if (ruleForOccurrence(day, id) != null) {
        val old = repeatEventsOn(day).firstOrNull { it.id == id }
        require(old != null) { "事件已不存在" }
        val next = transform(old).copy(id = id, originDay = null)
        return if (next == old) this else setRepeatException(day, id, next)
    }
    val events = baseEventsOn(day)
    val event = events.firstOrNull { it.id == id }
    require(event != null) { "事件已不存在" }
    val changed = transform(event).copy(id = id)
    if (changed == event) return this
    require(changed.start in 0..1439 && changed.end in 1..MAX_EVENT_MINUTES && changed.start < changed.end) { "事件时间无效" }
    val category = categories.firstOrNull { it.id == changed.categoryId }
    require(category != null && (changed.child.isEmpty() || category.children.any { it.id == changed.child })) { "分类引用不完整" }
    // Moving/resizing a historical label is allowed; assigning a new category requires an active one.
    if (changed.categoryId != event.categoryId || changed.child != event.child) {
        require(!category.archived && (changed.child.isEmpty() || category.children.any { it.id == changed.child && !it.archived })) { "所选分类不可用" }
    }
    return copy(createdEvents = createdEvents + (day to events.map { if (it.id == id) changed else it }))
}

private fun AppData.removeEvent(day: Long, id: String): AppData {
    val anchor = eventsOn(day).firstOrNull { it.id == id }?.originDay ?: error("事件已不存在")
    return removeAnchoredEvent(anchor, id)
}
private fun AppData.removeAnchoredEvent(day: Long, id: String): AppData {
    if (ruleForOccurrence(day, id) != null) return setRepeatException(day, id, null)
    val events = baseEventsOn(day)
    require(events.any { it.id == id }) { "事件已不存在" }
    return copy(createdEvents = createdEvents + (day to events.filterNot { it.id == id }))
}

private fun AppData.addEvent(day: Long, event: DemoEvent): AppData {
    val category = categories.firstOrNull { it.id == event.categoryId && !it.archived }
    require(category != null && (event.child.isEmpty() || category.children.any { it.id == event.child && !it.archived })) {
        "所选分类已不可用，请重新选择"
    }
    require(event.start in 0..1439 && event.end in 1..MAX_EVENT_MINUTES && event.start < event.end) { "事件时间无效" }
    val events = baseEventsOn(day)
    require(event.id.isNotBlank() && createdEvents.values.flatten().none { it.id == event.id }) { "事件ID无效或重复" }
    return copy(createdEvents = createdEvents + (day to (events + event)))
}

private fun AppData.saveDayAsTemplate(day: Long, name: String): AppData {
    require(validTemplateName(name)) { "请输入模板名称，不能含换行" }
    return copy(templates = templates + ScheduleTemplate(UUID.randomUUID().toString(), name,
        baseEventsOn(day).map { it.copy(id = UUID.randomUUID().toString(), scheduleIdentity = null, dayOffset = 0, originDay = null) }))
}

private fun AppData.renameTemplate(id: String, name: String): AppData {
    require(validTemplateName(name)) { "请输入模板名称，不能含换行" }
    require(templates.any { it.id == id }) { "模板已不存在" }
    // Past labels belong to the saved history; rename changes no event or revision.
    return copy(templates = templates.map { if (it.id == id) it.copy(name = name) else it })
}

private fun AppData.setDefaultTemplate(id: String?, today: Long): AppData {
    LocalDate.ofEpochDay(today)
    require(defaultHistory.lastOrNull()?.fromDay?.let { it <= today } != false) { "系统日期早于最近一次模板变更，请先检查日期" }
    val template = id?.let { key -> templates.firstOrNull { it.id == key } }
    require(id == null || template != null) { "模板已不存在" }
    require(template?.archived != true) { "请先恢复已归档模板" }
    val next = DefaultTemplateVersion(today, template?.id, template?.name.orEmpty(), template?.revision ?: 0, template?.events.orEmpty())
    val current = defaultOn(today)
    if (current?.templateId == id && current?.revision == next.revision && current?.events.orEmpty() == next.events) return this
    return copy(defaultHistory = defaultHistory.filter { it.fromDay < today } + next)
}

private fun AppData.updateTemplateFromDay(id: String, day: Long, today: Long): AppData {
    val old = templates.firstOrNull { it.id == id }
    require(old != null) { "模板已不存在" }
    val updated = old.copy(revision = Math.addExact(old.revision, 1),
        events = baseEventsOn(day).map { it.copy(id = UUID.randomUUID().toString(), scheduleIdentity = null, dayOffset = 0, originDay = null) })
    val result = copy(templates = templates.map { if (it.id == id) updated else it })
    return if (defaultHistory.lastOrNull()?.templateId == id) result.setDefaultTemplate(id, today) else result
}

// Archiving changes availability only; immutable past snapshots and independent dates survive.
// Save a detached template draft. Never writes createdEvents or absorbs repeat instances.
private fun AppData.saveTemplateDraft(id: String?, name: String, events: List<DemoEvent>, today: Long): AppData {
    require(validTemplateName(name)) { "请输入模板名称，不能含换行" }
    val old = id?.let { key -> templates.firstOrNull { it.id == key } }
    require(id == null || old != null) { "模板已不存在" }
    if (old?.events == events && old.name == name) return this
    val updated = ScheduleTemplate(old?.id ?: UUID.randomUUID().toString(), name, events.toList(),
        if (old == null) 1 else if (old.events == events) old.revision else Math.addExact(old.revision, 1), old?.archived ?: false)
    val result = copy(templates = if (old == null) templates + updated else templates.map { if (it.id == id) updated else it })
    return (if (old != null && old.events != events && defaultHistory.lastOrNull()?.templateId == id)
        result.setDefaultTemplate(id, today) else result).also { it.validate() }
}

private fun AppData.templateDraftEvents(id: String?, sourceDay: Long?): List<DemoEvent> =
    if (id != null) templates.first { it.id == id }.events.toList()
    else sourceDay?.let { day -> baseEventsOn(day).filter { categoryAvailable(it, day) }.map { event -> event.copy(id = UUID.randomUUID().toString(), scheduleIdentity = null, dayOffset = 0, originDay = null) } } ?: emptyList()

private fun AppData.archiveTemplate(id: String, archived: Boolean, today: Long): AppData {
    require(templates.any { it.id == id }) { "模板已不存在" }
    val result = if (archived && defaultHistory.lastOrNull()?.templateId == id) setDefaultTemplate(null, today) else this
    return result.copy(templates = result.templates.map { if (it.id == id) it.copy(archived = archived) else it })
}

private fun AppData.deleteTemplate(id: String, today: Long, clearDefault: Boolean = false): AppData {
    require(templates.any { it.id == id }) { "模板已不存在" }
    val isDefault = defaultHistory.lastOrNull()?.templateId == id
    require(!isDefault || clearDefault) { "请先更换默认模板，或明确选择删除并改为默认空白" }
    val result = if (isDefault) setDefaultTemplate(null, today) else this
    return result.copy(templates = result.templates.filterNot { it.id == id })
}

// Replace only proven template-origin events; manual and repeat occurrences survive.
private fun AppData.importTemplate(day: Long, templateId: String, replace: Boolean = false): AppData {
    val template = templates.firstOrNull { it.id == templateId }
    require(template != null && !template.archived) { "模板已不存在或已归档" }
    require(!hasArchivedLabels(template.events, categories)) { "模板含已归档分类，请先恢复分类或编辑模板" }
    var result = this
    if (replace) {
        eventsOn(day).filter {
            (it.importedTemplateId != null || it.scheduleIdentity?.startsWith("template-") == true) &&
                ruleForOccurrence(it.originDay ?: day, it.id) == null
        }.forEach { result = result.removeEvent(day, it.id) }
        result = result.copy(createdEvents = result.createdEvents + (day to result.baseEventsOn(day)))
    }
    val used = (result.createdEvents.values.flatten() + templates.flatMap { it.events } +
        defaultHistory.flatMap { it.events }).mapTo(mutableSetOf()) { it.id }
    val imported = template.events.map { event ->
        var id: String
        do { id = UUID.randomUUID().toString() } while (!used.add(id))
        event.copy(id = id, scheduleIdentity = null, dayOffset = 0, originDay = null, importedTemplateId = templateId)
    }
    return result.copy(createdEvents = result.createdEvents +
        (day to (result.baseEventsOn(day) + imported))).also { it.validate() }
}

private fun AppData.applyTemplate(day: Long, id: String, replaceConfirmed: Boolean = false): AppData {
    val template = templates.firstOrNull { it.id == id }
    require(template != null) { "模板已不存在" }
    require(replaceConfirmed || (day !in createdEvents && baseEventsOn(day).isEmpty())) { "请先确认替换本日安排" }
    return copy(createdEvents = createdEvents + (day to template.events.map { it.copy(id = UUID.randomUUID().toString(), scheduleIdentity = null, dayOffset = 0, originDay = null, importedTemplateId = id) }))
}

// Clear all generated/recorded schedules while preserving reusable templates,
// categories, appearance and other preferences. Both default inheritance and
// recurrence must be reset; otherwise "deleted" schedules reappear on other days.
private fun AppData.clearAllSchedules(): AppData = copy(
    createdEvents = emptyMap(),
    defaultHistory = emptyList(),
    repeatRules = emptyList(),
    repeatExceptions = emptyList()
)

private fun AppData.makeDayBlank(day: Long, replaceConfirmed: Boolean = false): AppData {
    require(replaceConfirmed || baseEventsOn(day).isEmpty()) { "请先确认清空本日安排" }
    return copy(createdEvents = createdEvents + (day to emptyList()))
}

private fun AppData.restoreDayInheritance(day: Long, replaceConfirmed: Boolean = false): AppData {
    require(replaceConfirmed || day !in createdEvents) { "请先确认移除本日独立安排" }
    return copy(createdEvents = createdEvents - day)
}

private fun AppData.validate() {
    validateRepeats()
    categoryPauses.forEach { p ->
        LocalDate.ofEpochDay(p.fromDay); p.untilDay?.let { LocalDate.ofEpochDay(it) }
        require(p.untilDay == null || p.untilDay > p.fromDay) { "分类归档日期无效" }
        val c = categories.firstOrNull { it.id == p.parentId }
        require(c != null && (p.childId == null || c.children.any { it.id == p.childId })) { "归档分类引用无效" }
        require(p.untilDay != null || if (p.childId == null) c.archived else c.children.first { it.id == p.childId }.archived) { "分类归档状态不一致" }
    }
    categoryPauses.groupBy { it.parentId to it.childId }.values.forEach { spans ->
        require(spans.zipWithNext().all { (a, b) -> a.untilDay != null && a.untilDay <= b.fromDay }) { "分类归档区间重叠或倒序" }
    }
    require(settings.recentColors.size <= 8 && settings.recentColors.all { parseThemeHex(it) != null }) { "最近颜色无效" }
    require(settings.mainRows in 1..2 && settings.childRows in 1..2) { "分类行数必须为一行或两行" }
    require(settings.themeMode in listOf("system", "light", "dark")) { "外观模式无效" }
    require(parseThemeHex(settings.themeHex) != null) { "主题色须为六位 HEX" }
    require(settings.fontScale in .85f..1.3f && settings.spacingScale in .85f..1.2f &&
        settings.cornerDp in 0f..28f && settings.eventOpacity in .3f..1f) { "外观参数超出范围" }
    createdEvents.forEach { (_, events) ->
        require(events.map { it.id }.distinct().size == events.size) { "事件ID重复" }
        require(events.all { it.id.isNotBlank() }) { "事件ID为空" }
    }
    val ids = mutableSetOf<String>()
    categories.forEach { category ->
        require(category.id.isNotBlank() && ids.add(category.id)) { "分类 ID 无效或重复" }
        checkName(null, category.id, category.name)
        category.children.forEach { child ->
            require(child.id.isNotBlank() && ids.add(child.id)) { "子分类 ID 无效或重复" }
            checkName(category.id, child.id, child.name)
        }
    }
    require(templates.map { it.id }.distinct().size == templates.size) { "模板ID重复" }
    templates.forEach {
        require(it.id.isNotBlank() && validTemplateName(it.name) && it.revision > 0) { "模板信息无效" }
    }
    require(defaultHistory.zipWithNext().all { (a, b) -> a.fromDay < b.fromDay }) { "默认模板历史顺序无效" }
    defaultHistory.forEach {
        LocalDate.ofEpochDay(it.fromDay)
        require(if (it.templateId == null) it.events.isEmpty() && it.revision == 0
            else it.templateId.isNotBlank() && validTemplateName(it.name) && it.revision > 0) { "默认模板历史无效" }
    }
    val currentDefault = defaultHistory.lastOrNull()
    if (currentDefault?.templateId != null) {
        require(templates.any { it.id == currentDefault.templateId && !it.archived && it.revision == currentDefault.revision && it.events == currentDefault.events }) {
            "当前默认模板引用不完整"
        }
    }
    val eventLists = createdEvents.values + templates.map { it.events } + defaultHistory.map { it.events }
    eventLists.forEach { events ->
        require(events.all { it.id.isNotBlank() } && events.map { it.id }.distinct().size == events.size) { "事件ID无效或重复" }
    }
    eventLists.flatten().forEach { event ->
        require(event.scheduleIdentity == null || Regex("template-[0-9a-f]{64}").matches(event.scheduleIdentity)) { "日程接口身份格式无效" }
        require(event.start in 0..1439 && event.end in 1..MAX_EVENT_MINUTES && event.start < event.end) { "事件时间无效" }
        val category = categories.firstOrNull { it.id == event.categoryId }
        require(category != null && (event.child.isEmpty() || category.children.any { it.id == event.child })) {
            "事件分类引用不完整"
        }
    }
    (templates.flatMap { it.events } + defaultHistory.flatMap { it.events }).forEach {
        require(it.dayOffset == 0 && it.originDay == null) { "模板不能包含绝对日期偏移" }
    }
    createdEvents.forEach { (day, events) ->
        LocalDate.ofEpochDay(day)
        events.forEach {
            require(it.originDay == null && it.dayOffset in -366000..366000) { "事件日期偏移无效" }
            LocalDate.ofEpochDay(day + it.dayOffset); LocalDate.ofEpochDay(day + it.dayOffset + (it.end - 1) / 1440)
        }
    }
    createdEvents.values.forEach { events ->
        val keys = events.map { it.scheduleIdentity ?: "event:${it.id}" }
        require(keys.all { it.isNotBlank() } && keys.distinct().size == keys.size) { "日程接口身份冲突" }
    }
    // Legacy v1-v6 may reuse native IDs on different independent dates. Preserve them,
    // but reject an edit that would bring two such IDs onto the same visible date.
    val extendedDays = createdEvents.flatMap { (anchor, events) -> events.filter {
        it.end > 1440 || it.dayOffset != 0
    }.flatMap { (anchor + it.dayOffset..anchor + it.dayOffset + (it.end - 1) / 1440).toList() } }.toSet()
    extendedDays.forEach { eventsOn(it) }
}

private fun eventsToJson(events: List<DemoEvent>): JSONArray = JSONArray().also { array ->
    events.forEach { array.put(JSONObject().put("start", it.start).put("end", (it.end - 1) % 1440 + 1)
        .put("endDayOffset", (it.end - 1) / 1440).put("dayOffset", it.dayOffset)
        .put("categoryId", it.categoryId).put("childId", it.child).put("id", it.id)
        .also { row -> it.scheduleIdentity?.let { key -> row.put("scheduleIdentity", key) }; it.importedTemplateId?.let { id -> row.put("importedTemplateId", id) } }) }
}

private fun eventsFromJson(array: JSONArray, legacyDay: Long? = null): List<DemoEvent> =
    List(array.length()) { index ->
        val event = array.getJSONObject(index)
        require(event.getInt("start") in 0..1439 && event.getInt("end") in 1..1440 &&
            event.optInt("endDayOffset", 0) in 0..365 && event.optInt("dayOffset", 0) in -366000..366000) { "事件日期时间字段无效" }
        val id = if (legacyDay != null) event.optString("id").takeIf { it.isNotBlank() } ?: "legacy-$legacyDay-$index"
            else event.getString("id")
        DemoEvent(event.getInt("start"), event.getInt("end") + event.optInt("endDayOffset", 0) * 1440, event.getString("categoryId"), event.getString("childId"), id,
            if (event.has("scheduleIdentity")) event.getString("scheduleIdentity") else null, event.optInt("dayOffset", 0), if (event.has("importedTemplateId")) event.getString("importedTemplateId") else null)
    }.also { events ->
        require(events.all { it.id.isNotBlank() } && events.map { it.id }.distinct().size == events.size) { "事件ID无效或重复" }
    }

private fun AppData.toJson(): String {
    val categoryArray = JSONArray()
    categories.forEach { category ->
        val children = JSONArray()
        category.children.forEach {
            children.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("color", it.color.toArgb()).put("archived", it.archived))
        }
        categoryArray.put(JSONObject().put("id", category.id).put("name", category.name)
            .put("color", category.color.toArgb()).put("archived", category.archived).put("children", children))
    }
    val days = JSONArray()
    createdEvents.toSortedMap().forEach { (day, events) ->
        days.put(JSONObject().put("day", day).put("events", eventsToJson(events)))
    }
    val templateArray = JSONArray()
    templates.forEach { template ->
        templateArray.put(JSONObject().put("id", template.id).put("name", template.name)
            .put("revision", template.revision).put("archived", template.archived).put("events", eventsToJson(template.events)))
    }
    val history = JSONArray()
    defaultHistory.forEach { version ->
        history.put(JSONObject().put("fromDay", version.fromDay).put("templateId", version.templateId ?: JSONObject.NULL)
            .put("name", version.name).put("revision", version.revision).put("events", eventsToJson(version.events)))
    }
    return JSONObject().put("version", 8).put("categories", categoryArray).put("days", days)
        .put("templates", templateArray).put("defaultHistory", history)
        .put("categoryPauses", JSONArray().also { a -> categoryPauses.forEach { p -> a.put(JSONObject().put("parentId", p.parentId)
            .put("childId", p.childId ?: JSONObject.NULL).put("fromDay", p.fromDay).put("untilDay", p.untilDay ?: JSONObject.NULL)) } })
        .put("repeatRules", repeatsToJson(repeatRules)).put("repeatExceptions", exceptionsToJson(repeatExceptions))
        .put("settings", JSONObject().put("mainRows", settings.mainRows)
            .put("childRows", settings.childRows).put("darkTheme", settings.darkTheme)
            .put("themeMode", settings.themeMode).put("themeHex", settings.themeHex)
            .put("fontScale", settings.fontScale.toDouble()).put("spacingScale", settings.spacingScale.toDouble())
            .put("cornerDp", settings.cornerDp.toDouble()).put("eventOpacity", settings.eventOpacity.toDouble())
            .put("recentColors", JSONArray(settings.recentColors))).toString()
}

private fun dataFromJson(text: String): AppData {
    val root = JSONObject(text)
    val version = root.getInt("version")
    require(version in 1..8) { "不支持此数据版本" }
    val categoryArray = root.getJSONArray("categories")
    val categories = List(categoryArray.length()) { index ->
        val category = categoryArray.getJSONObject(index)
        val children = category.getJSONArray("children")
        Category(category.getString("id"), category.getString("name"), Color(category.getInt("color")),
            List(children.length()) { childIndex ->
                val child = children.getJSONObject(childIndex)
                Subcategory(child.getString("id"), child.getString("name"), Color(child.getInt("color")), child.getBoolean("archived"))
            }, category.getBoolean("archived"))
    }
    val days = root.getJSONArray("days")
    val events = mutableMapOf<Long, List<DemoEvent>>()
    repeat(days.length()) { index ->
        val day = days.getJSONObject(index)
        val key = day.getLong("day")
        require(key !in events) { "日期记录重复" }
        events[key] = eventsFromJson(day.getJSONArray("events"), key.takeIf { version < 3 })
    }
    val prefs = root.optJSONObject("settings")
    val legacyDark = prefs?.optBoolean("darkTheme", true) ?: true
    val settings = if (prefs == null) DisplaySettings() else DisplaySettings(
        mainRows = prefs.optInt("mainRows", 2), childRows = prefs.optInt("childRows", 1), darkTheme = legacyDark,
        themeMode = prefs.optString("themeMode", if (legacyDark) "dark" else "light"),
        themeHex = prefs.optString("themeHex", "527F93").trim().removePrefix("#").uppercase(Locale.ROOT),
        fontScale = prefs.optDouble("fontScale", 1.0).toFloat(),
        spacingScale = prefs.optDouble("spacingScale", 1.0).toFloat(), cornerDp = prefs.optDouble("cornerDp", 16.0).toFloat(),
        eventOpacity = prefs.optDouble("eventOpacity", 1.0).toFloat(),
        recentColors = prefs.optJSONArray("recentColors")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList())
    if (version < 3) {
        // v1/v2 stored per-day deltas over the seven built-in demo events.
        // Preserve EXACT effective order/IDs of every touched date, including deletion-only records.
        val removedArray = root.optJSONArray("removedDemoIds") ?: JSONArray()
        val removed = mutableMapOf<Long, List<String>>()
        repeat(removedArray.length()) { index ->
            val item = removedArray.getJSONObject(index)
            val day = item.getLong("day")
            require(day !in removed) { "日期记录重复" }
            val ids = item.getJSONArray("ids")
            removed[day] = List(ids.length()) { ids.getString(it) }.also { list ->
                require(list.all { id -> demoEvents.any { it.id == id } }) { "无效的示例事件ID" }
            }
        }
        val snapshots = (events.keys + removed.keys).associateWith { day ->
            stableDayEvents(demoEvents, events[day].orEmpty(), removed[day].orEmpty())
        }
        // This migration-only template keeps untouched existing dates and historical statistics intact.
        // A genuinely fresh install starts blank; no demo rows are introduced there.
        val template = ScheduleTemplate("legacy-demo-template", "原有示例日程",
            demoEvents.map { it.copy(id = "legacy-template-${it.id}") })
        return AppData(categories, snapshots, settings, listOf(template), listOf(DefaultTemplateVersion(
            LocalDate.MIN.toEpochDay(), template.id, template.name, template.revision, template.events))).also { it.validate() }
    }
    val templateArray = root.getJSONArray("templates")
    val templates = List(templateArray.length()) { index ->
        val item = templateArray.getJSONObject(index)
        ScheduleTemplate(item.getString("id"), item.getString("name"), eventsFromJson(item.getJSONArray("events")), item.getInt("revision"),
            if (item.has("archived")) item.getBoolean("archived") else false)
    }
    val history = root.getJSONArray("defaultHistory")
    val versions = List(history.length()) { index ->
        val item = history.getJSONObject(index)
        require(item.has("templateId")) { "默认模板字段缺失" }
        DefaultTemplateVersion(item.getLong("fromDay"), if (item.isNull("templateId")) null else item.getString("templateId"),
            item.getString("name"), item.getInt("revision"), eventsFromJson(item.getJSONArray("events")))
    }
    return AppData(categories, events.toMap(), settings, templates, versions,
        if (version >= 4) repeatsFromJson(root.getJSONArray("repeatRules"), version >= 5) else emptyList(),
        if (version >= 4) exceptionsFromJson(root.getJSONArray("repeatExceptions")) else emptyList(),
        if (version >= 5) root.getJSONArray("categoryPauses").let { a -> List(a.length()) { i ->
            val p = a.getJSONObject(i)
            require(p.has("childId") && p.has("untilDay")) { "分类归档区间字段缺失" }
            CategoryPause(p.getString("parentId"), if (p.isNull("childId")) null else p.getString("childId"),
                p.getLong("fromDay"), if (p.isNull("untilDay")) null else p.getLong("untilDay"))
        } } else emptyList()).withScheduleIdentities().also { it.validate() }
}

// External backups are bounded, strict JSON. Android JSONObject alone accepts duplicate keys,
// trailing garbage, fractional integers and coercions; none are safe for a replacement import.
private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
private class StrictBackupJson(private val source: String) {
    private var at = 0
    private var nodes = 0
    private fun space() { while (at < source.length && source[at] in " \r\n\t") at++ }
    private fun take(c: Char): Boolean { space(); return if (at < source.length && source[at] == c) { at++; true } else false }
    private fun need(c: Char) { require(take(c)) { "JSON 结构不完整" } }
    private fun string(): String {
        space(); val start = at; need('"')
        while (at < source.length) {
            val c = source[at++]
            if (c == '"') {
                val decoded = org.json.JSONTokener(source.substring(start, at)).nextValue() as String
                require(decoded.indices.all { i -> when {
                    decoded[i].isHighSurrogate() -> i + 1 < decoded.length && decoded[i + 1].isLowSurrogate()
                    decoded[i].isLowSurrogate() -> i > 0 && decoded[i - 1].isHighSurrogate()
                    else -> true
                } }) { "JSON 包含不完整的 Unicode 字符" }
                return decoded
            }
            require(c >= ' ') { "JSON 字符串含控制字符" }
            if (c == '\\') {
                require(at < source.length) { "JSON 转义不完整" }
                val escaped = source[at++]
                require(escaped in "\"\\/bfnrtu") { "JSON 转义无效" }
                if (escaped == 'u') repeat(4) {
                    require(at < source.length && source[at++] in "0123456789abcdefABCDEF") { "JSON Unicode 转义无效" }
                }
            }
        }
        error("JSON 字符串不完整")
    }
    private fun value(depth: Int): Any {
        require(depth <= 32 && ++nodes <= 500000) { "备份结构过大或嵌套过深" }
        space(); require(at < source.length) { "备份为空或不完整" }
        return when (source[at]) {
            '{' -> {
                at++; val result = JSONObject(); val keys = mutableSetOf<String>()
                if (!take('}')) {
                    do { val key = string(); require(keys.add(key)) { "JSON 字段重复：$key" }
                        need(':'); result.put(key, value(depth + 1))
                    } while (take(','))
                    need('}')
                }; result
            }
            '[' -> {
                at++; val result = JSONArray()
                if (!take(']')) { do { result.put(value(depth + 1)) } while (take(',')); need(']') }; result
            }
            '"' -> string()
            't', 'f', 'n' -> {
                val token = when (source[at]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                require(source.startsWith(token, at)) { "JSON 值无效" }; at += token.length
                when (token) { "true" -> true; "false" -> false; else -> JSONObject.NULL }
            }
            else -> {
                val start = at
                while (at < source.length && source[at] in "-+0123456789.eE") at++
                val number = source.substring(start, at)
                require(number.length <= 64) { "JSON 数字过长" }
                require(number.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) { "JSON 数字无效" }
                if (number.none { it in ".eE" }) number.toLongOrNull() ?: error("整数超出范围")
                else number.toDouble().also { require(it.isFinite()) { "数字超出范围" } }
            }
        }
    }
    fun read(): JSONObject {
        val result = value(0); space()
        require(at == source.length && result is JSONObject) { "备份不是完整 JSON 对象" }
        return result
    }
}

private fun validateBackupShape(root: JSONObject) {
    val version = root.get("version")
    require(version is Long && version in 1..8) { "不支持此数据版本" }
    fun fields(obj: JSONObject, strings: Set<String> = emptySet(), ints: Set<String> = emptySet(),
        longs: Set<String> = emptySet(), bools: Set<String> = emptySet(), arrays: Set<String> = emptySet(),
        objects: Set<String> = emptySet(), floats: Set<String> = emptySet(), nullable: Set<String> = emptySet(),
        optional: Set<String> = emptySet()) {
        val allowed = strings + ints + longs + bools + arrays + objects + floats
        require(obj.keys().asSequence().all { it in allowed }) { "备份含未知字段，请使用匹配的应用版本" }
        require((allowed - optional).all { obj.has(it) }) { "备份字段缺失" }
        allowed.filter { obj.has(it) }.forEach { key ->
            val value = obj.get(key)
            require(if (value == JSONObject.NULL) key in nullable else when (key) {
                in strings -> value is String
                in ints -> value is Long && value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                in longs -> value is Long
                in bools -> value is Boolean
                in arrays -> value is JSONArray
                in objects -> value is JSONObject
                in floats -> value is Number && value.toDouble().isFinite()
                else -> false
            }) { "备份字段类型无效：$key" }
        }
    }
    fun each(array: JSONArray, action: (JSONObject) -> Unit) { repeat(array.length()) { action(array.getJSONObject(it)) } }
    fun events(array: JSONArray, legacy: Boolean = false) { each(array) {
        fields(it, strings = setOf("id", "categoryId", "childId") + (if (version >= 8) setOf("importedTemplateId") else emptySet()) + (if (version >= 6) setOf("scheduleIdentity") else emptySet()),
            ints = setOf("start", "end") + (if (version >= 7) setOf("endDayOffset", "dayOffset") else emptySet()), optional = (if (legacy) setOf("id") else emptySet()) + setOf("scheduleIdentity", "importedTemplateId"))
        require(version >= 8 || it.optInt("endDayOffset", 0) in 0..1) { "多天事件需要JSON v8" }
    } }
    fields(root, ints = setOf("version"), arrays = setOf("categories", "days", "templates", "defaultHistory", "repeatRules", "repeatExceptions", "categoryPauses", "removedDemoIds"),
        objects = setOf("settings"), optional = buildSet {
            add("removedDemoIds"); if (version < 5) add("categoryPauses")
            if (version < 4) addAll(listOf("repeatRules", "repeatExceptions"))
            if (version < 3) addAll(listOf("templates", "defaultHistory", "settings"))
        })
    // Version downgrading must not silently discard newer sections.
    require(version >= 5 || !root.has("categoryPauses")) { "归档区间与版本不匹配" }
    require(version >= 4 || !root.has("repeatRules") && !root.has("repeatExceptions")) { "重复数据与版本不匹配" }
    require(version >= 3 || !root.has("templates") && !root.has("defaultHistory")) { "模板数据与版本不匹配" }
    require(version < 3 || !root.has("removedDemoIds")) { "旧版日期数据与版本不匹配" }
    each(root.getJSONArray("categories")) { c ->
        fields(c, strings = setOf("id", "name"), ints = setOf("color"), bools = setOf("archived"), arrays = setOf("children"))
        each(c.getJSONArray("children")) { fields(it, strings = setOf("id", "name"), ints = setOf("color"), bools = setOf("archived")) }
    }
    each(root.getJSONArray("days")) { fields(it, longs = setOf("day"), arrays = setOf("events")); events(it.getJSONArray("events"), version < 3) }
    root.optJSONObject("settings")?.let {
        val modern = setOf("themeMode", "themeHex", "fontScale", "spacingScale", "cornerDp", "eventOpacity", "recentColors")
        fields(it, strings = setOf("themeMode", "themeHex"), ints = setOf("mainRows", "childRows"), bools = setOf("darkTheme"),
            floats = setOf("fontScale", "spacingScale", "cornerDp", "eventOpacity"), arrays = setOf("recentColors"),
            optional = if (version < 5) modern + setOf("mainRows", "childRows", "darkTheme") else setOf("eventOpacity"))
        it.optJSONArray("recentColors")?.let { a -> repeat(a.length()) { i -> require(a.get(i) is String) { "最近颜色无效" } } }
    }
    root.optJSONArray("removedDemoIds")?.let { a -> each(a) {
        fields(it, longs = setOf("day"), arrays = setOf("ids"))
        val ids = it.getJSONArray("ids"); repeat(ids.length()) { i -> require(ids.get(i) is String) { "旧版事件ID无效" } }
    } }
    root.optJSONArray("templates")?.let { a -> each(a) {
        fields(it, strings = setOf("id", "name"), ints = setOf("revision"), bools = setOf("archived"), arrays = setOf("events"), optional = if (version < 5) setOf("archived") else emptySet())
        events(it.getJSONArray("events"))
    } }
    root.optJSONArray("defaultHistory")?.let { a -> each(a) {
        fields(it, strings = setOf("templateId", "name"), ints = setOf("revision"), longs = setOf("fromDay"), arrays = setOf("events"), nullable = setOf("templateId"))
        events(it.getJSONArray("events"))
    } }
    root.optJSONArray("repeatRules")?.let { a -> each(a) { rule ->
        fields(rule, strings = setOf("id"), arrays = setOf("versions"))
        each(rule.getJSONArray("versions")) {
            fields(it, strings = setOf("name", "status"), ints = setOf("revision"), longs = setOf("fromDay", "startDay", "endDay"),
                arrays = setOf("events", "weekdays"), bools = if (version >= 5) setOf("removed") else emptySet(), nullable = setOf("endDay"))
            events(it.getJSONArray("events"))
            val days = it.getJSONArray("weekdays"); repeat(days.length()) { i -> require(days.get(i) is Long && days.getLong(i) in 1..7) { "星期无效" } }
        }
    } }
    root.optJSONArray("repeatExceptions")?.let { a -> each(a) {
        fields(it, strings = setOf("ruleId"), longs = setOf("day"), arrays = setOf("replacement"), nullable = setOf("replacement"))
        if (!it.isNull("replacement")) events(it.getJSONArray("replacement"))
    } }
    root.optJSONArray("categoryPauses")?.let { a -> each(a) {
        fields(it, strings = setOf("parentId", "childId"), longs = setOf("fromDay", "untilDay"), nullable = setOf("childId", "untilDay"))
    } }
}

private data class BackupCandidate(val data: AppData, val sourceVersion: Int, val generatedAt: String?)
private data class PendingRestore(val candidate: BackupCandidate, val before: AppData)
private fun AppData.backupBytes(): ByteArray {
    validate()
    val document = JSONObject().put("format", "com.vera.timebricks.backup").put("backupVersion", 1)
        .put("generatedAt", Instant.now().toString()).put("generator", "TimeBricks Android / JSON v8")
        .put("data", JSONObject(toJson())).toString().toByteArray(Charsets.UTF_8)
    require(document.size <= MAX_BACKUP_BYTES) { "数据超过 32 MiB 备份上限" }
    check(parseBackup(document).data == this) { "备份往返校验失败" }
    return document
}
internal fun verifyTimeBricksBackupPayload(bytes: ByteArray) { parseBackup(bytes) }

internal fun snapshotTimeBricksForAutoBackup(application: Application): ByteArray =
    synchronized(ReminderSystem.lock) { LocalStore(application).load().backupBytes() }

private fun parseBackup(bytes: ByteArray): BackupCandidate {
    require(bytes.isNotEmpty() && bytes.size <= MAX_BACKUP_BYTES) { "备份为空或超过 32 MiB" }
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val root = StrictBackupJson(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")).read()
    val data: JSONObject
    var generated: String? = null
    if (root.has("format")) {
        require(root.keys().asSequence().toSet() == setOf("format", "backupVersion", "generatedAt", "generator", "data")) { "备份头字段无效" }
        require(root.get("format") == "com.vera.timebricks.backup" && root.get("backupVersion") == 1L) { "不支持此备份格式" }
        require(root.get("generator") is String && root.get("generatedAt") is String) { "生成信息无效" }
        generated = root.getString("generatedAt"); Instant.parse(generated)
        data = root.getJSONObject("data")
    } else data = root // Existing v1-v7 local JSON files remain importable.
    validateBackupShape(data)
    val parsed = dataFromJson(data.toString()) // Builds a wholly separate immutable graph, then validates references.
    return BackupCandidate(parsed, data.getInt("version"), generated)
}
private fun java.io.InputStream.readBackupBytes(limit: Int = MAX_BACKUP_BYTES): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer); if (count < 0) break
        require(output.size().toLong() + count <= limit) { "备份文件超过大小限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

// Verify and sync before the single commit point. All failures before rename leave the old file
// byte-for-byte intact. Never perform a fallible check after a successful atomic replacement.
private fun commitLocalBytes(path: File, bytes: ByteArray,
    writeStage: (File, ByteArray) -> Unit = { staged, contents ->
        java.io.FileOutputStream(staged).use { it.write(contents); it.flush(); it.fd.sync() }
    }, replace: (File, File) -> Unit) {
    val staged = File.createTempFile("timebricks-restore-", ".tmp", path.parentFile)
    try {
        writeStage(staged, bytes)
        check(staged.readBytes().contentEquals(bytes)) { "临时文件校验失败" }
        replace(staged, path)
    } finally { runCatching { staged.delete() } }
}

private fun AppData.backupSummary(): String =
    "${categories.size} 个分类 / ${categories.sumOf { it.children.size }} 个子类\n" +
        "${createdEvents.size} 个独立日期 / ${createdEvents.values.sumOf { it.size }} 条安排\n" +
        "${templates.size} 个模板 / ${defaultHistory.size} 个默认模板历史版本\n" +
        "${repeatRules.size} 条重复规则 / ${repeatRules.sumOf { it.versions.size }} 个规则版本 / ${repeatExceptions.size} 个单次例外\n" +
        "${categoryPauses.size} 段归档区间 / 全部颜色、主题及布局设置"


// Schedule exports are read-only projections. JSON remains the complete application backup.
private data class ScheduleExportRange(val first: Long, val last: Long) {
    init {
        require(first <= last && last - first in 0..3659) { "请选择不超过 3660 天的有效日期范围" }
        require(LocalDate.ofEpochDay(first).year in 1..9998 && LocalDate.ofEpochDay(last).year in 1..9998) { "导出日期须在公元 1–9998 年内" }
    }
}
private enum class ScheduleExportFormat(val label: String, val extension: String, val mime: String) {
    TXT("TXT", "txt", "text/plain"), PNG("图片", "png", "image/png"), ICS("ICS", "ics", "text/calendar")
}
private data class ExportEntry(val day: Long, val event: DemoEvent, val title: String, val color: Int)
private data class ExportDay(val day: Long, val entries: List<ExportEntry>)
private data class ScheduleProjection(val range: ScheduleExportRange, val days: List<ExportDay>) {
    val count: Int get() = days.flatMap { it.entries }.map { (it.event.originDay ?: it.day) to it.event.id }.distinct().size
}
private fun AppData.exportEntry(day: Long, event: DemoEvent): ExportEntry {
    val parent = categories.first { it.id == event.categoryId }
    val child = parent.children.firstOrNull { it.id == event.child }
    return ExportEntry(day, event, parent.name + (child?.let { "·${it.name}" } ?: ""), (child?.color ?: parent.color).toArgb())
}
private fun AppData.scheduleProjection(range: ScheduleExportRange): ScheduleProjection {
    validate()
    var count = 0
    val days = (range.first..range.last).map { day ->
        val events = eventsOn(day).sortedBy { it.start }
        count += events.size
        require(count <= 20000) { "导出超过 20000 条安排，请缩小日期范围" }
        ExportDay(day, events.map { exportEntry(day, it) })
    }
    return ScheduleProjection(range, days)
}
private fun exportClock(minute: Int) = clockMinute(minute)
private fun ScheduleProjection.toScheduleText(): ByteArray = buildString {
    append("时间积木 TimeBricks\r\n")
    append("${LocalDate.ofEpochDay(range.first)} — ${LocalDate.ofEpochDay(range.last)}\r\n")
    append("共 $count 条安排 · 计划时间\r\n")
    days.forEach { day ->
        append("\r\n${LocalDate.ofEpochDay(day.day)}\r\n")
        if (day.entries.isEmpty()) append("无安排\r\n")
        day.entries.forEach { row ->
            append("${exportClock(row.event.start)}–${exportClock(row.event.end)}  ${row.title}  ${row.event.end - row.event.start} 分钟")
            if (row.event.isCrossDay()) append(" · 当日 ${row.event.end.coerceAtMost(1440) - row.event.start.coerceAtLeast(0)} 分钟")
            append("\r\n")
        }
    }
}.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BACKUP_BYTES) { "导出文件超过 32 MiB" } }

private fun icsText(text: String): String = text.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
    .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
// RFC 5545: fold at 75 UTF-8 octets, never inside a code point, including the continuation space.
private fun foldIcsLine(line: String): String {
    val result = StringBuilder(); var octets = 0; var index = 0
    while (index < line.length) {
        val point = line.codePointAt(index); val text = String(Character.toChars(point))
        val size = text.toByteArray(Charsets.UTF_8).size
        if (octets + size > 75) { result.append("\r\n "); octets = 1 }
        result.append(text); octets += size; index += Character.charCount(point)
    }
    return result.append("\r\n").toString()
}
private fun icsDateTime(day: Long, minute: Int): String = LocalDate.ofEpochDay(day).atStartOfDay().plusMinutes(minute.toLong())
    .format(DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT))
private fun exportUid(vararg parts: String): String = UUID.nameUUIDFromBytes(
    JSONArray(parts.toList()).toString().toByteArray(Charsets.UTF_8)).toString() + "@timebricks.local"

// One component per active rule version, with RRULE / EXDATE / RECURRENCE-ID.
// Bounds are explicit: an exported file never schedules events outside the chosen dates.
// Wall-clock (floating) times preserve this app's date+minute semantics across time zones.
private fun AppData.scheduleIcs(projection: ScheduleProjection, generated: Instant = Instant.now()): ByteArray {
    val range = projection.range
    val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//TimeBricks//Schedule Export 1//ZH-CN", "CALSCALE:GREGORIAN")
    val stamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC).format(generated)
    fun eventLines(uid: String, day: Long, event: DemoEvent, description: String, extra: List<String> = emptyList()): List<String> =
        listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:$stamp", "DTSTART:${icsDateTime(day, event.start)}",
            "DTEND:${icsDateTime(day, event.end)}", "SUMMARY:${icsText(exportEntry(day, event).title)}",
            "DESCRIPTION:${icsText(description)}", "TRANSP:OPAQUE") + extra + "END:VEVENT"
    if (projection.days.any { d -> d.entries.any { it.event.isCrossDay() || it.event.dayOffset != 0 } }) {
        val seen = mutableSetOf<Pair<Long, String>>()
        projection.days.forEach { d -> d.entries.forEach { row ->
            val anchor = row.event.originDay ?: d.day
            if (seen.add(anchor to row.event.id)) lines += eventLines(
                exportUid("occurrence", anchor.toString(), row.event.id), d.day, row.event,
                "TimeBricks · 完整事件快照（跨日只导出一次）")
        } }
        lines += "END:VCALENDAR"
        return lines.joinToString("") { foldIcsLine(it) }.toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_BACKUP_BYTES) { "导出文件超过 32 MiB" } }
    }
    // Base events are snapshots of templates or independent dates; never absorb recurring instances.
    projection.days.forEach { day ->
        val repeatIds = repeatRules.map { occurrenceId(it.id, day.day) }.toSet()
        day.entries.filter { it.event.id !in repeatIds }.forEach { row ->
            lines += eventLines(exportUid("base", day.day.toString(), row.event.id), day.day, row.event, "TimeBricks · 计划安排")
        }
    }
    val exceptions = repeatExceptions.associateBy { it.ruleId to it.day }
    repeatRules.forEach { rule ->
        rule.versions.forEachIndexed { index, version ->
            val low = maxOf(range.first, version.fromDay)
            val high = minOf(range.last, rule.versions.getOrNull(index + 1)?.fromDay?.minus(1) ?: range.last)
            if (low > high || version.status != "active") return@forEachIndexed
            val source = version.event
            val nominal = if (source == null) emptyList() else (low..high).filter(version::matches)
            val actual = (low..high).mapNotNull { day ->
                val exception = exceptions[rule.id to day]
                val event = if (exception != null) exception.replacement else source?.takeIf { version.matches(day) }
                event?.takeIf { categoryAvailable(it, day) }?.let { day to it }
            }.toMap()
            if (actual.isEmpty()) return@forEachIndexed
            // Include bounds in series identity: importing distinct range files must not overwrite
            // each other's RRULE. Re-importing an identical file has stable IDs (client policy varies).
            val uid = exportUid("rule", rule.id, version.fromDay.toString(), range.first.toString(), range.last.toString())
            if (source != null && nominal.isNotEmpty()) {
                val recurrence = if (version.weekdays.size == 7) "FREQ=DAILY" else
                    "FREQ=WEEKLY;BYDAY=" + version.weekdays.sorted().joinToString(",") { listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[it - 1] }
                val extra = mutableListOf("RRULE:$recurrence;UNTIL=${icsDateTime(nominal.last(), source.start)}")
                nominal.filter { it !in actual }.forEach { extra += "EXDATE:${icsDateTime(it, source.start)}" }
                lines += eventLines(uid, nominal.first(), source, "TimeBricks · 重复规则：${version.name}", extra)
                nominal.filter { it in actual && exceptions.containsKey(rule.id to it) }.forEach { day ->
                    lines += eventLines(uid, day, actual.getValue(day), "TimeBricks · 单次修改：${version.name}",
                        listOf("RECURRENCE-ID:${icsDateTime(day, source.start)}"))
                }
            }
            // Old one-off edits may survive a later weekday/start/end change or removed-category
            // version. They are real dated exceptions, not members of this version's RRULE.
            val nominalSet = nominal.toSet()
            actual.filterKeys { it !in nominalSet }.forEach { (day, event) ->
                lines += eventLines(exportUid("detached", rule.id, day.toString()), day, event, "TimeBricks · 保留的单次修改：${version.name}")
            }
        }
    }
    lines += "END:VCALENDAR"
    return lines.joinToString("") { foldIcsLine(it) }.toByteArray(Charsets.UTF_8)
        .also { require(it.size <= MAX_BACKUP_BYTES) { "导出文件超过 32 MiB" } }
}

private data class ScheduleDocument(val bytes: ByteArray, val format: ScheduleExportFormat, val filename: String)

private data class ScheduleImageBlock(val day: ExportDay?, val entry: ExportEntry?, val top: Int, val height: Int)
private data class ScheduleImagePlan(val height: Int, val blocks: List<ScheduleImageBlock>)
private fun scheduleImagePlan(projection: ScheduleProjection, titleHeight: (String) -> Int): ScheduleImagePlan {
    var y = 164
    val blocks = mutableListOf<ScheduleImageBlock>()
    fun add(day: ExportDay?, entry: ExportEntry?, height: Int) {
        require(y.toLong() + height + 40 <= 12000) { "图片内容过长，请缩小日期范围；也可使用 TXT 或 ICS" }
        blocks += ScheduleImageBlock(day, entry, y, height); y += height
    }
    projection.days.forEach { day ->
        add(day, null, 68)
        if (day.entries.isEmpty()) add(null, null, 56)
        day.entries.forEach { entry -> add(null, entry, maxOf(104, titleHeight(entry.title) + 62)) }
    }
    return ScheduleImagePlan(y + 40, blocks)
}


/** Both entry points use the exact UI resolver; the backup path is also an adapter reference. */
internal fun scheduleRowsFromBackup(bytes: ByteArray, date: LocalDate, zone: java.time.ZoneId): List<ScheduleApiRow> =
    parseBackup(if (AutoBackupZip.isZip(bytes)) AutoBackupZip.unpack(bytes) else bytes).data.scheduleApiRows(date, zone)

internal fun scheduleRowsV2FromBackup(bytes: ByteArray, date: LocalDate, zone: java.time.ZoneId): List<ScheduleApiRow> =
    parseBackup(if (AutoBackupZip.isZip(bytes)) AutoBackupZip.unpack(bytes) else bytes).data.scheduleApiRows(date, zone, 2)

private fun AppData.scheduleApiRows(date: LocalDate, zone: java.time.ZoneId, apiVersion: Int = 1): List<ScheduleApiRow> {
    val day = date.toEpochDay()
    val effective = eventsOn(day)
    check(apiVersion >= 2 || effective.all { it.start in 0..1439 && it.end in 1..1440 && it.originDay == day && it.dayOffset == 0 }) { "TB_REQUIRES_API_V2" }
    val rows = effective.sortedWith(compareBy<DemoEvent> { it.start }.thenBy { it.id }).map { event ->
        val parent = categories.first { it.id == event.categoryId }
        val child = parent.children.firstOrNull { it.id == event.child }
        val anchor = event.originDay ?: day
        val rule = ruleForOccurrence(anchor, event.id)
        val identity = if (rule != null) "rule:${rule.id}" else event.scheduleIdentity ?: "event:${event.id}"
        ScheduleApiRow(ScheduleContract.instanceId(anchor, identity), identity, event.id, rule?.id,
            if (rule != null) "repeat" else if (event.scheduleIdentity != null) "template" else "dated",
            date.toString(), ScheduleContract.timestamp(date, event.start, zone),
            ScheduleContract.timestamp(date, event.end, zone), zone.id, Math.floorMod(event.start, 1440), if (apiVersion == 1 && Math.floorMod(event.end, 1440) == 0) 1440 else Math.floorMod(event.end, 1440),
            parent.name + (child?.let { "·${it.name}" } ?: ""), parent.id, parent.name,
            child?.id, child?.name, (child?.color ?: parent.color).toArgb(),
            rule != null && repeatExceptions.any { it.ruleId == rule.id && it.day == anchor },
            originDate = LocalDate.ofEpochDay(anchor).toString(),
            startDate = date.plusDays(Math.floorDiv(event.start, 1440).toLong()).toString(),
            endDate = date.plusDays(Math.floorDiv(event.end, 1440).toLong()).toString(),
            segmentStart = event.start.coerceAtLeast(0), segmentEnd = event.end.coerceAtMost(1440),
            crossDay = event.isCrossDay(), durationMinutes = event.end - event.start)
    }
    check(rows.map { it.instanceId }.distinct().size == rows.size) { "日程接口身份冲突" }
    // A DST gap can map two different wall-clock values to a reversed/empty interval.
    // Do not export an invalid interval or silently omit it from an authoritative snapshot.
    check(rows.all { it.endMs > it.startMs }) { "TB_TIME_ZONE_CONFLICT" }
    return rows
}

internal fun readScheduleApiSnapshot(application: Application, date: LocalDate?, apiVersion: Int = 1): ScheduleApiSnapshot =
    synchronized(ReminderSystem.lock) {
        val base = File(application.filesDir, "timebricks-data-v1.json")
        // Match AtomicFile's backup preference without performing its recovery writes.
        val backup = File(base.path + ".bak")
        val file = if (backup.exists()) backup else base
        check(file.exists()) { "TB_NOT_INITIALIZED" }
        val bytes = file.inputStream().use { it.readBackupBytes() }
        val candidate = parseBackup(bytes)
        val zone = java.time.ZoneId.systemDefault()
        val token = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
        ScheduleApiSnapshot(date?.let { candidate.data.scheduleApiRows(it, zone, apiVersion) }.orEmpty(), token,
            candidate.sourceVersion, zone.id)
    }

private class LocalStore(private val application: Application) {
    private val path = File(application.filesDir, "timebricks-data-v1.json")
    private val atomic = AtomicFile(path)

    fun load(): AppData = synchronized(ReminderSystem.lock) {
        // Legacy AtomicFile backups must also go through openRead for recovery.
        if (!path.exists() && !File(path.path + ".bak").exists()) return AppData()
        return atomic.openRead().bufferedReader(Charsets.UTF_8).use { dataFromJson(it.readText()) }
    }

    fun save(data: AppData) = synchronized(ReminderSystem.lock) {
        data.validate()
        val encoded = data.toJson().toByteArray(Charsets.UTF_8)
        // Legacy backup recovery is completed by load(); refuse to supersede an unresolved backup.
        check(!File(path.path + ".bak").exists()) { "旧版备份尚未恢复，请重新启动应用" }
        commitLocalBytes(path, encoded) { staged, target -> android.system.Os.rename(staged.path, target.path) }
        ReminderSystem.refresh(application)
        ScheduleWidgets.dataChanged(application)
        StatusScheduleOverlay.requestRefresh(application)
    }
}

// ViewModel survives rotation; one writer publishes state only after durable save.
private class TimeBricksStore(application: Application) : AndroidViewModel(application) {
    private val local = LocalStore(application)
    var data by mutableStateOf(AppData())
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var loadError by mutableStateOf("")
        private set
    var notice by mutableStateOf("")
        private set

    private val undoHistory = mutableStateListOf<AppData>()
    private val redoHistory = mutableStateListOf<AppData>()
    val canUndo: Boolean get() = undoHistory.isNotEmpty() && !busy
    val canRedo: Boolean get() = redoHistory.isNotEmpty() && !busy

    var pendingRestore by mutableStateOf<PendingRestore?>(null)
        private set
    var backupMessage by mutableStateOf("")
        private set
    var backupProgress by mutableStateOf("")
        private set
    var restoreGeneration by mutableIntStateOf(0)
        private set
    private var exportDocument: ByteArray? = null
    private var backupSelection: String? = null
    private var importBefore: AppData? = null

    fun dismissBackupMessage() { backupMessage = "" }
    fun cancelRestore() { if (!busy) { pendingRestore = null; AutoBackupSystem.resumeRestore(getApplication<Application>()) } }
    fun pickerFailed() {
        exportDocument = null; importBefore = null; backupSelection = null; busy = false
        AutoBackupSystem.resumeRestore(getApplication<Application>())
        backupProgress = ""; backupMessage = "无法打开系统文件选择器。本机数据未改动。"
    }
    fun prepareExport(openPicker: () -> Unit) {
        if (!loaded || busy) return
        busy = true; backupProgress = "正在生成备份…"
        val snapshot = data
        viewModelScope.launch {
            try {
                exportDocument = withContext(Dispatchers.IO) { snapshot.backupBytes() }
                backupProgress = ""; backupSelection = "export"
                openPicker()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                exportDocument = null; backupSelection = null; busy = false; backupProgress = ""
                backupMessage = "备份未导出：${backupError(error)}。本机数据未改动。"
            }
        }
    }
    fun exportTo(uri: android.net.Uri?) {
        val bytes = exportDocument
        exportDocument = null
        if (backupSelection != "export") {
            backupMessage = "备份操作已中断，请重新导出。本机数据未改动。"; return
        }
        backupSelection = null
        if (uri == null) { busy = false; return }
        if (bytes == null) { busy = false; backupMessage = "备份已失效，请重新导出。"; return }
        backupProgress = "正在写入备份…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeVerifiedDocument(getApplication<Application>().contentResolver, uri, bytes)
                }
                backupMessage = "备份已导出并校验。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                backupMessage = "导出未确认完成：${backupError(error)}。目标位置可能留下不完整文件，请删除后重试；本机数据未改动。"
            } finally { backupProgress = ""; busy = false }
        }
    }
    fun beginImportSelection(): Boolean {
        if (!loaded || busy || pendingRestore != null) return false
        busy = true; backupSelection = "import"; importBefore = data
        AutoBackupSystem.pauseRestore()
        return true
    }
    fun importFrom(uri: android.net.Uri?) {
        if (backupSelection != "import") {
            backupMessage = "导入操作已中断，请重新选择文件。本机数据未改动。"; return
        }
        backupSelection = null
        val before = importBefore; importBefore = null
        if (uri == null || before == null) { busy = false; AutoBackupSystem.resumeRestore(getApplication<Application>()); return }
        backupProgress = "正在校验备份…"
        viewModelScope.launch {
            try {
                val candidate = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "文件位置无效" }
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBackupBytes(AutoBackupZip.MAX_ZIP_BYTES)
                    } ?: error("无法读取备份")
                    parseBackup(if (AutoBackupZip.isZip(bytes)) AutoBackupZip.unpack(bytes) else bytes)
                }
                pendingRestore = PendingRestore(candidate, before)
            } catch (cancelled: CancellationException) {
                AutoBackupSystem.resumeRestore(getApplication<Application>()); throw cancelled
            } catch (error: Exception) {
                backupMessage = "无法导入：${backupError(error)}。本机数据未改动。"
                AutoBackupSystem.resumeRestore(getApplication<Application>())
            } finally { backupProgress = ""; busy = false }
        }
    }
    fun confirmRestore() {
        val pending = pendingRestore ?: return
        if (busy || !loaded) return
        if (data != pending.before) {
            pendingRestore = null; backupMessage = "本机数据已变化，请重新选择备份确认。"; AutoBackupSystem.resumeRestore(getApplication<Application>()); return
        }
        busy = true; backupProgress = "正在恢复…"
        // Cancellation/rotation must not separate disk commit from in-memory publication.
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            try {
                withContext(Dispatchers.IO) { local.save(pending.candidate.data) }
                data = pending.candidate.data
                undoHistory.clear(); redoHistory.clear()
                pendingRestore = null; restoreGeneration++
                notice = "备份已恢复"; backupMessage = "备份已恢复，撤销记录已清空。"
            } catch (error: Exception) {
                backupMessage = "恢复失败：${backupError(error)}。本机原数据和撤销记录均未改动。"
            } finally {
                backupProgress = ""; busy = false
                if (pendingRestore == null) AutoBackupSystem.resumeRestore(getApplication<Application>())
            }
        }
    }

    var scheduleExportMessage by mutableStateOf("")
        private set
    var scheduleExportProgress by mutableStateOf("")
        private set
    private var scheduleDocument: ScheduleDocument? = null
    fun dismissScheduleExportMessage() { scheduleExportMessage = "" }
    fun schedulePickerFailed() {
        scheduleDocument = null; busy = false; scheduleExportProgress = ""
        scheduleExportMessage = "无法打开系统文件选择器，日程未改动。"
    }
    fun prepareScheduleExport(range: ScheduleExportRange, format: ScheduleExportFormat, dark: Boolean, openPicker: (ScheduleDocument) -> Unit) {
        if (!loaded || busy) return
        busy = true; scheduleExportProgress = "正在生成${format.label}…"
        val snapshot = data
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    val projection = snapshot.scheduleProjection(range)
                    val bytes = when (format) {
                        ScheduleExportFormat.TXT -> projection.toScheduleText()
                        ScheduleExportFormat.PNG -> schedulePng(projection, dark)
                        ScheduleExportFormat.ICS -> snapshot.scheduleIcs(projection)
                    }
                    ScheduleDocument(bytes, format, "TimeBricks-${LocalDate.ofEpochDay(range.first)}-${LocalDate.ofEpochDay(range.last)}.${format.extension}")
                }
                scheduleDocument = document; scheduleExportProgress = ""
                openPicker(document)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                scheduleDocument = null; busy = false; scheduleExportProgress = ""
                scheduleExportMessage = "未导出：${backupError(error)}。日程未改动。"
            } catch (_: OutOfMemoryError) {
                scheduleDocument = null; busy = false; scheduleExportProgress = ""
                scheduleExportMessage = "可用内存不足，请缩小日期范围。日程未改动。"
            }
        }
    }
    fun saveScheduleDocument(uri: android.net.Uri?) {
        val document = scheduleDocument; scheduleDocument = null
        if (document == null) { scheduleExportMessage = "导出操作已中断，请重新导出。"; return }
        if (uri == null) { busy = false; return }
        scheduleExportProgress = "正在保存${document.format.label}…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { writeVerifiedDocument(getApplication<Application>().contentResolver, uri, document.bytes) }
                scheduleExportMessage = "${document.format.label}已导出并校验。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                scheduleExportMessage = "导出未确认完成：${backupError(error)}。目标位置可能留下不完整文件，请删除后重试；日程未改动。"
            } finally { busy = false; scheduleExportProgress = "" }
        }
    }

    fun undo() {
        if (busy || undoHistory.isEmpty()) return
        val previous = undoHistory.last()
        val current = data
        change("已撤销", { previous }, trackUndo = false, onSaved = {
            undoHistory.removeAt(undoHistory.lastIndex)
            redoHistory.add(current)
        })
    }
    fun redo() {
        if (busy || redoHistory.isEmpty()) return
        val next = redoHistory.last()
        val current = data
        change("已重做", { next }, trackUndo = false, onSaved = {
            redoHistory.removeAt(redoHistory.lastIndex)
            undoHistory.add(current)
        })
    }

    init { reload() }

    fun reload() {
        if (busy) return
        busy = true
        loadError = ""
        viewModelScope.launch {
            try {
                data = withContext(Dispatchers.IO) { local.load() }
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                loadError = "本地数据读取失败，原文件未改动。请重试；不要清除应用数据。"
            } finally { busy = false }
        }
    }

    fun change(success: String, transform: (AppData) -> AppData, trackUndo: Boolean = false, onSaved: () -> Unit = {}) {
        if (!loaded || busy) return
        val next = try { transform(data).also { it.validate() } } catch (error: IllegalArgumentException) {
            notice = error.message ?: "操作未完成"
            return
        }
        val before = data
        // A tap, identical edit or unchanged default does not create an override/history entry.
        if (next == before) { onSaved(); return }
        busy = true
        notice = "正在保存…"
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            try {
                withContext(Dispatchers.IO) { local.save(next) }
                data = next
                if (!trackUndo && success != "已撤销" && success != "已重做") {
                    undoHistory.clear(); redoHistory.clear()
                }
                if (trackUndo) {
                    undoHistory.add(before)
                    if (undoHistory.size > 50) undoHistory.removeAt(0)
                    redoHistory.clear()
                }
                notice = success
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice = "保存失败，本次修改未确认，请重试。"
            } finally { busy = false }
        }
    }
}

private fun schedulePng(projection: ScheduleProjection, dark: Boolean): ByteArray {
    val foreground = if (dark) 0xFFF2F2F2.toInt() else 0xFF20272D.toInt()
    val secondary = if (dark) 0xFFA6A6A6.toInt() else 0xFF525F69.toInt()
    val titlePaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = foreground }
    fun titleLayout(title: String) = android.text.StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, 620)
        .setIncludePad(false).setLineSpacing(4f, 1f).build()
    val plan = scheduleImagePlan(projection) { titleLayout(it).height }
    val bitmap = try { android.graphics.Bitmap.createBitmap(900, plan.height, android.graphics.Bitmap.Config.ARGB_8888) }
        catch (_: OutOfMemoryError) { throw IllegalArgumentException("图片过大，请缩小日期范围") }
    try {
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(if (dark) 0xFF070707.toInt() else 0xFFF5F6F7.toInt())
        fun text(value: String, x: Float, baseline: Float, size: Float, color: Int = foreground) {
            paint.color = color; paint.textSize = size; canvas.drawText(value, x, baseline, paint)
        }
        text("时间积木", 32f, 58f, 36f)
        text("TimeBricks · 计划日程", 32f, 94f, 22f, secondary)
        text("${LocalDate.ofEpochDay(projection.range.first)} — ${LocalDate.ofEpochDay(projection.range.last)}", 32f, 136f, 26f)
        plan.blocks.forEach { block ->
            val top = block.top.toFloat(); val entry = block.entry
            if (block.day != null) text(LocalDate.ofEpochDay(block.day.day).toString(), 32f, top + 45, 30f)
            else if (entry == null) text("无安排", 42f, top + 32, 24f, secondary)
            else {
                paint.color = if (dark) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
                canvas.drawRoundRect(24f, top, 876f, top + block.height - 10, 18f, 18f, paint)
                paint.color = entry.color
                canvas.drawRoundRect(38f, top + 18, 44f, top + block.height - 28, 3f, 3f, paint)
                text(exportClock(entry.event.start), 66f, top + 39, 28f)
                text(exportClock(entry.event.end), 66f, top + 76, 24f, secondary)
                val layout = titleLayout(entry.title)
                canvas.save(); canvas.translate(224f, top + 17); layout.draw(canvas); canvas.restore()
                text("共 ${entry.event.end - entry.event.start} 分钟",
                    224f, top + 17 + layout.height + 30, 22f, secondary)
            }
        }
        return java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) { "图片编码失败" }
            output.toByteArray().also { require(it.size <= MAX_BACKUP_BYTES) { "图片超过 32 MiB，请缩小日期范围" } }
        }
    } finally { bitmap.recycle() }
}

// Shared SAF path: no filesystem-path conversion, broad storage permissions or persistent grants.
private fun writeVerifiedDocument(resolver: android.content.ContentResolver, uri: android.net.Uri, bytes: ByteArray) {
    require(uri.scheme == "content") { "文件位置无效" }
    val descriptor = resolver.openFileDescriptor(uri, "wt") ?: error("无法打开目标文件")
    android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use {
        it.write(bytes); it.flush()
        if (android.system.OsConstants.S_ISREG(android.system.Os.fstat(it.fd).st_mode)) it.fd.sync()
    }
    val saved = resolver.openInputStream(uri)?.use { it.readBackupBytes() } ?: error("无法回读导出文件")
    check(saved.contentEquals(bytes)) { "导出文件回读校验失败" }
}

private data class ManagedCategory(val id: String, val name: String, val color: Color, val archived: Boolean)
private data class CategoryAction(val id: String, val name: String, val delete: Boolean)

@Composable
private fun CategoryManager(
    store: TimeBricksStore, parentId: String?, onClose: () -> Unit,
    onChildren: (String) -> Unit, onParents: () -> Unit
) {
    val data = store.data
    val parent = data.categories.firstOrNull { it.id == parentId }
    val allEntries = if (parentId == null) data.categories.map { ManagedCategory(it.id, it.name, it.color, it.archived) }
        else parent?.children.orEmpty().map { ManagedCategory(it.id, it.name, it.color, it.archived) }
    var showArchived by rememberSaveable(parentId) { mutableStateOf(false) }
    val entries = allEntries.filter { it.archived == showArchived }
    var editorOpen by remember(parentId) { mutableStateOf(false) }
    var editing by remember(parentId) { mutableStateOf<ManagedCategory?>(null) }
    var action by remember(parentId) { mutableStateOf<CategoryAction?>(null) }
    var openId by remember(parentId, showArchived) { mutableStateOf<String?>(null) }
    var draftOrder by remember(parentId, showArchived, data.categories) { mutableStateOf<List<String>?>(null) }
    val displayed = draftOrder?.mapNotNull { id -> entries.firstOrNull { it.id == id } } ?: entries
    val listState = key(parentId, showArchived) { rememberLazyListState() }
    var draggedId by remember(parentId, showArchived) { mutableStateOf<String?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var draggedHeight by remember { mutableIntStateOf(0) }
    val latestDisplayed by rememberUpdatedState(displayed)
    val latestBusy by rememberUpdatedState(store.busy)
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val maxScrollStep = with(density) { 12.dp.toPx() }
    fun moveAtPointer() {
        val id = draggedId ?: return
        val items = latestDisplayed
        val from = items.indexOfFirst { it.id == id }
        val center = pointerY - grabOffset + draggedHeight / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != id && center >= it.offset && center < it.offset + it.size &&
                items.any { entry -> entry.id == it.key }
        } ?: return
        val to = items.indexOfFirst { it.id == target.key }
        if (from >= 0 && to >= 0 && from != to) draftOrder = movedItem(items, from, to - from).map { it.id }
    }
    val latestMove by rememberUpdatedState({ moveAtPointer() })
    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            val info = listState.layoutInfo
            val scroll = when {
                pointerY < info.viewportStartOffset + edge ->
                    -maxScrollStep * ((info.viewportStartOffset + edge - pointerY) / edge).coerceIn(0f, 1f)
                pointerY > info.viewportEndOffset - edge ->
                    maxScrollStep * ((pointerY - info.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (scroll != 0f) listState.scrollBy(scroll)
            latestMove()
            delay(16)
        }
    }
    LaunchedEffect(store.busy, draggedId) {
        if (!store.busy && draggedId == null) draftOrder = null
    }
    val latestFinish by rememberUpdatedState({
        val order = draftOrder
        if (draggedId != null && order != null && order != entries.map { it.id }) {
            store.change("顺序已保存", { it.reorderCategories(parentId, order) })
        }
        draggedId = null
        if (!store.busy) draftOrder = null
    })
    fun edit(entry: ManagedCategory) { openId = null; editing = entry; editorOpen = true }
    fun archive(entry: ManagedCategory) {
        openId = null
        if (entry.archived) store.change("分类已恢复", { it.setCategoryAvailability(parentId, entry.id, false, LocalDate.now().toEpochDay()) }, true)
        else action = CategoryAction(entry.id, entry.name, false)
    }
    fun leave() {
        when {
            draggedId != null -> { draggedId = null; draftOrder = null }
            openId != null -> openId = null
            showArchived -> showArchived = false
            parentId != null -> onParents()
            else -> onClose()
        }
    }
    Dialog(onDismissRequest = { if (!store.busy) leave() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { if (!store.busy) leave() }
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = BricksDimensions.pageInset)) {
                BricksHeader(if (showArchived) "已归档" else parent?.name ?: "时间分类",
                    !store.busy && draggedId == null, { leave() }, action = {
                        if (!showArchived) TextButton(enabled = !store.busy && draggedId == null, onClick = { showArchived = true; openId = null }) { Text("已归档") }
                    })
                if (parent?.archived == true) Text("主分类已归档，其子分类暂不显示在规划页。", color = Secondary, fontSize = 12.sp)
                if (store.notice.startsWith("保存失败")) Text(store.notice, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                LazyColumn(state = listState, userScrollEnabled = draggedId == null,
                    modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(parentId, showArchived) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { position ->
                                if (!latestBusy) {
                                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                        position.y >= it.offset && position.y < it.offset + it.size &&
                                            latestDisplayed.any { entry -> entry.id == it.key }
                                    }
                                    if (item != null) {
                                        openId = null
                                        draggedId = item.key as String
                                        pointerY = position.y
                                        grabOffset = position.y - item.offset
                                        draggedHeight = item.size
                                    }
                                }
                            },
                            onDrag = { change, amount ->
                                if (draggedId != null) {
                                    change.consume()
                                    pointerY += amount.y
                                    latestMove()
                                }
                            },
                            onDragEnd = { latestFinish() },
                            onDragCancel = { draggedId = null; draftOrder = null }
                        )
                    }, contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (displayed.isEmpty()) item(key = "empty") {
                        Text(if (showArchived) "暂无已归档分类" else "暂无分类，点击下方添加",
                            color = Secondary, modifier = Modifier.padding(vertical = 20.dp))
                    }
                    itemsIndexed(displayed, key = { _, entry -> entry.id }) { index, entry ->
                        val isDragging = draggedId == entry.id
                        val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.id }
                        val references = data.references(parentId ?: entry.id, if (parentId == null) null else entry.id)
                        val hasChildren = parentId == null && data.categories.first { it.id == entry.id }.children.isNotEmpty()
                        val canDelete = true
                        val enabled = !store.busy && draggedId == null
                        CompactCategoryRow(entry, openId == entry.id, enabled, canDelete,
                            detail = if (parentId == null) "›" else "",
                            modifier = Modifier.zIndex(if (isDragging) 1f else 0f).graphicsLayer {
                                translationY = if (isDragging && itemInfo != null) pointerY - grabOffset - itemInfo.offset else 0f
                                shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                            }.semantics {
                                customActions = if (!enabled) emptyList() else buildList {
                                    add(CustomAccessibilityAction("编辑") { edit(entry); true })
                                    add(CustomAccessibilityAction(if (entry.archived) "恢复" else "归档") { archive(entry); true })
                                    if (canDelete) add(CustomAccessibilityAction("删除") { action = CategoryAction(entry.id, entry.name, true); true })
                                    if (index > 0) add(CustomAccessibilityAction("上移") {
                                        store.change("顺序已保存", { it.reorderCategories(parentId, movedItem(displayed, index, -1).map { item -> item.id }) }); true
                                    })
                                    if (index < displayed.lastIndex) add(CustomAccessibilityAction("下移") {
                                        store.change("顺序已保存", { it.reorderCategories(parentId, movedItem(displayed, index, 1).map { item -> item.id }) }); true
                                    })
                                }
                            },
                            onOpen = { open -> openId = if (open) entry.id else null },
                            onClick = {
                                if (openId != null) openId = null
                                else if (parentId == null) onChildren(entry.id) else edit(entry)
                            }, onEdit = { edit(entry) }, onArchive = { archive(entry) },
                            onDelete = { openId = null; action = CategoryAction(entry.id, entry.name, true) })
                    }
                    if (!showArchived) item(key = "add") {
                        Row(Modifier.fillMaxWidth().heightIn(min = BricksDimensions.listRow)
                            .clickable(enabled = !store.busy && draggedId == null && (parentId == null || parent != null), role = Role.Button) {
                                openId = null; editing = null; editorOpen = true
                            }.padding(horizontal = BricksDimensions.pageInset), verticalAlignment = Alignment.CenterVertically) {
                            Text("＋", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp)
                            Text(if (parentId == null) "添加主分类" else "添加子分类", color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }
    if (editorOpen) {
        CategoryEditor(editing, parent?.color ?: Color(0xFF4D706B), store.busy, store.notice, data.settings.recentColors,
            nameError = { name ->
                try { data.checkName(parentId, editing?.id, name); null }
                catch (error: IllegalArgumentException) { error.message }
            }, onDismiss = { editorOpen = false }, onSave = { name, color ->
                store.change("分类已保存，规划页已刷新", { it.editCategory(parentId, editing?.id, name, color).withRecentColor(colorHex(color)) },
                    onSaved = { editorOpen = false })
            })
    }
    action?.let { pending ->
        BricksDialog(onDismissRequest = { if (!store.busy) action = null },
            title = { Text("${if (pending.delete) "删除" else "归档"}「${pending.name}」？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (pending.delete) "删除该分类${if (parentId == null) "及其子分类" else ""}和 ${data.references(parentId ?: pending.id, if (parentId == null) null else pending.id)} 处关联安排，包括日程、模板和历史。重复规则中其他分类的记录与例外保留。可在本次运行中撤销。"
                        else "从今天起停止该分类${if (parentId == null) "及子分类" else ""}的安排，保留此前历史。恢复后从恢复当天起继续，已归档期间不会补回。")
                    if (store.notice.startsWith("保存失败")) Text(store.notice, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(enabled = !store.busy, onClick = {
                store.change(if (pending.delete) "分类已删除" else "分类已归档，历史事件保留",
                    { if (pending.delete) it.purgeCategory(parentId, pending.id) else it.setCategoryAvailability(parentId, pending.id, true, LocalDate.now().toEpochDay()) },
                    trackUndo = true, onSaved = { action = null })
            }) { Text(if (pending.delete) "删除" else "归档") } },
            dismissButton = { TextButton(enabled = !store.busy, onClick = { action = null }) { Text("取消") } })
    }
}

@Composable
private fun CompactCategoryRow(
    entry: ManagedCategory, open: Boolean, enabled: Boolean, canDelete: Boolean, detail: String,
    modifier: Modifier, onOpen: (Boolean) -> Unit, onClick: () -> Unit,
    onEdit: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val preferredActionWidth = with(density) { maxOf(60.dp, 28.sp.toDp() + 20.dp) }
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = BricksDimensions.gap).clip(RoundedCornerShape(BricksDimensions.pill))) {
    val actionWidth = minOf(preferredActionWidth, maxWidth / 3)
    val revealWidth = with(density) { (actionWidth * 3).toPx() }
    var swipe by remember { mutableFloatStateOf(0f) }
    var swiping by remember { mutableStateOf(false) }
    val latestOpen by rememberUpdatedState(open)
    val latestOnOpen by rememberUpdatedState(onOpen)
    val offset = if (swiping) swipe else if (open) -revealWidth else 0f
    Box(Modifier.fillMaxWidth()) {
        // Hidden actions cannot be focused or activated until the row is revealed.
        if (offset < 0f) Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            SwipeAction("编辑", actionWidth, enabled && open, MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer, onEdit)
            SwipeAction(if (entry.archived) "恢复" else "归档", actionWidth, enabled && open,
                MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, onArchive)
            SwipeAction("删除", actionWidth, enabled && open && canDelete,
                MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, onDelete)
        }
        Row(Modifier.fillMaxWidth().graphicsLayer { translationX = offset }.background(NavBg)
            .heightIn(min = BricksDimensions.listRow)
            .pointerInput(entry.id, enabled, revealWidth) {
                if (enabled) detectHorizontalDragGestures(
                    onDragStart = { swipe = if (latestOpen) -revealWidth else 0f; swiping = true },
                    onHorizontalDrag = { change, amount ->
                        change.consume(); swipe = (swipe + amount).coerceIn(-revealWidth, 0f)
                    },
                    onDragEnd = { latestOnOpen(swipe < -revealWidth / 3f); swiping = false },
                    onDragCancel = { swiping = false }
                )
            }.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(BricksDimensions.small)).background(entry.color))
            Text(entry.name, Modifier.weight(1f).padding(horizontal = BricksDimensions.pageInset), color = Primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            if (detail.isNotEmpty()) ChevronIcon()
        }

    }
    }
}

@Composable
private fun SwipeAction(label: String, width: Dp, enabled: Boolean, background: Color, ink: Color, onClick: () -> Unit) {
    Box(Modifier.width(width).fillMaxHeight().background(background)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = ink.copy(alpha = if (enabled) 1f else .4f), fontSize = 13.sp)
    }
}


private fun colorHex(color: Color): String = String.format(Locale.ROOT, "%06X", color.toArgb() and 0xFFFFFF)
private fun colorHsv(color: Color): List<Float> {
    val r = color.red; val g = color.green; val b = color.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
    val hue = if (delta == 0f) 0f else when (max) {
        r -> ((g - b) / delta % 6f) * 60f
        g -> ((b - r) / delta + 2f) * 60f
        else -> ((r - g) / delta + 4f) * 60f
    }
    return listOf((hue + 360f) % 360f, if (max == 0f) 0f else delta / max, max)
}
private val pickerPresets = listOf("4D706B", "81749D", "A08B6B", "758B72", "627F92", "956D82")

@Composable
private fun ColorValueRow(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = Primary, fontSize = 14.sp)
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color).border(1.dp, Secondary, RoundedCornerShape(8.dp)))
        ChevronIcon()
    }
}

@Composable
private fun ColorPickerDialog(initial: Color, recent: List<String>, onDismiss: () -> Unit, onSave: (Color) -> Unit) {
    val hsv = remember(initial) { colorHsv(initial) }
    var hue by rememberSaveable { mutableFloatStateOf(hsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(hsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(hsv[2]) }
    var hex by rememberSaveable { mutableStateOf(colorHex(initial)) }
    fun update(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h; saturation = s; brightness = v
        hex = colorHex(Color.hsv(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
    }
    fun acceptHex(value: String) {
        hex = value
        parseThemeHex(value)?.let { color ->
            val next = colorHsv(Color(color)); hue = next[0]; saturation = next[1]; brightness = next[2]
        }
    }
    val parsed = parseThemeHex(hex)
    BricksDialog(onDismissRequest = onDismiss, title = { Text("选择颜色") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val latestUpdate by rememberUpdatedState<(Float, Float) -> Unit>({ s, v -> update(s = s, v = v) })
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(BricksDimensions.medium))
                .testTag("color-square").semantics { contentDescription = "饱和度与明度，可使用下方 HEX 精确输入" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(); down.consume()
                        fun set(position: Offset) { latestUpdate((position.x / size.width).coerceIn(0f, 1f), 1f - (position.y / size.height).coerceIn(0f, 1f)) }
                        set(down.position)
                        do {
                            val event = awaitPointerEvent(); val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (pointer.pressed) set(pointer.position)
                            pointer.consume()
                        } while (event.changes.any { it.id == down.id && it.pressed })
                    }
                }) {
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val point = Offset(saturation * size.width, (1f - brightness) * size.height)
                drawCircle(Color.Black.copy(alpha = .6f), 8.dp.toPx(), point, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 8.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
            }
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) })))
                Slider(hue, { update(h = it) }, valueRange = 0f..360f,
                    colors = SliderDefaults.colors(thumbColor = Color.hsv(hue, 1f, 1f), activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent), modifier = Modifier.semantics { contentDescription = "色相" })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(parsed?.let { Color(it) } ?: initial)
                    .border(1.dp, Secondary, RoundedCornerShape(8.dp)))
                OutlinedTextField(hex, { acceptHex(it) }, label = { Text("HEX") }, singleLine = true,
                    isError = parsed == null, modifier = Modifier.weight(1f))
            }
            val swatches = (recent + pickerPresets).distinct().take(12)
            swatches.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { value ->
                        val color = Color(parseThemeHex(value)!!)
                        Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp)).background(color)
                            .semantics { contentDescription = "颜色 #$value" }.clickable { acceptHex(value) })
                    }
                    repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { onSave(Color(it)) } }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun CategoryEditor(
    entry: ManagedCategory?, initialColor: Color, busy: Boolean, notice: String,
    recentColors: List<String>, nameError: (String) -> String?, onDismiss: () -> Unit, onSave: (String, Color) -> Unit
) {
    var name by rememberSaveable(entry?.id) { mutableStateOf(entry?.name ?: "") }
    var hex by rememberSaveable(entry?.id) { mutableStateOf(colorHex(entry?.color ?: initialColor)) }
    var picking by remember { mutableStateOf(false) }
    val error = nameError(name.trim())
    val color = Color(parseThemeHex(hex)!!)
    BricksDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (entry == null) "添加分类" else "编辑分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("名称") }, singleLine = true,
                    isError = name.isNotEmpty() && error != null, modifier = Modifier.fillMaxWidth())
                if (name.isNotEmpty() && error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                ColorValueRow("颜色", color, !busy) { picking = true }
                if (notice.startsWith("保存失败")) Text(notice, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = !busy && error == null, onClick = { onSave(name.trim(), color) }) { Text("保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
    if (picking) ColorPickerDialog(color, recentColors, { picking = false }) { hex = colorHex(it); picking = false }
}

// Shared chrome for management pages; domain transactions stay in AppData/Store.
@Composable
private fun BricksHeader(title: String, enabled: Boolean, onBack: () -> Unit,
    action: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Primary, fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        action?.invoke(this)
        TextButton(enabled = enabled, onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun BricksDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    title: @Composable () -> Unit, text: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        dismissButton = dismissButton, title = title, text = text,
        shape = RoundedCornerShape(BricksDimensions.pill), containerColor = NavBg,
        titleContentColor = Primary, textContentColor = Secondary, tonalElevation = 0.dp)
}

private data class RowAction(val label: String, val destructive: Boolean = false, val run: () -> Unit)

@Composable
private fun CompactManagementRow(id: String, title: String, subtitle: String = "", color: Color? = null,
    selected: Boolean = false, enabled: Boolean, open: Boolean, onOpen: (Boolean) -> Unit,
    onClick: () -> Unit, actions: List<RowAction>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(BricksDimensions.pill))) {
        val actionWidth = minOf(with(density) { maxOf(60.dp, 28.sp.toDp() + 20.dp) }, maxWidth / maxOf(1, actions.size))
        val revealWidth = with(density) { (actionWidth * actions.size).toPx() }
        var drag by remember(id) { mutableFloatStateOf(0f) }
        var dragging by remember(id) { mutableStateOf(false) }
        val latestOpen by rememberUpdatedState(open)
        val latestOnOpen by rememberUpdatedState(onOpen)
        val offset = if (dragging) drag else if (open) -revealWidth else 0f
        Box(Modifier.fillMaxWidth()) {
            if (offset < 0f) Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
                actions.forEach { action ->
                    SwipeAction(action.label, actionWidth, enabled && open,
                        if (action.destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        if (action.destructive) MaterialTheme.colorScheme.onErrorContainer else Primary) {
                        onOpen(false); action.run()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().graphicsLayer { translationX = offset }.background(NavBg)
                .heightIn(min = BricksDimensions.listRow.coerceAtLeast(48.dp))
                .testTag(id).semantics {
                    customActions = if (enabled) actions.map { action ->
                        CustomAccessibilityAction(action.label) { onOpen(false); action.run(); true }
                    } else emptyList()
                }.pointerInput(id, enabled, revealWidth) {
                    if (enabled && actions.isNotEmpty()) detectHorizontalDragGestures(
                        onDragStart = { drag = if (latestOpen) -revealWidth else 0f; dragging = true },
                        onHorizontalDrag = { change, amount -> change.consume(); drag = (drag + amount).coerceIn(-revealWidth, 0f) },
                        onDragEnd = { latestOnOpen(drag < -revealWidth / 3); dragging = false },
                        onDragCancel = { dragging = false })
                }.clickable(enabled = enabled, role = Role.Button) {
                    if (open) onOpen(false) else onClick()
                }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (color != null) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = Primary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotEmpty()) Text(subtitle, color = Secondary, fontSize = 12.sp)
                }
                if (selected) SelectedMark()
            }
        }
    }
}

@Composable
private fun StoreNotice(store: TimeBricksStore, modifier: Modifier = Modifier) {
    val snacks = remember { SnackbarHostState() }
    var seen by remember { mutableStateOf(store.notice) }
    LaunchedEffect(store.notice, store.busy) {
        if (!store.busy && seen != store.notice) {
            seen = store.notice
            if (seen.isNotEmpty()) snacks.showSnackbar(seen)
        }
    }
    SnackbarHost(snacks, modifier)
}

private data class TemplateRequest(
    val kind: String, val templateId: String?, val day: Long, val today: Long, val snapshot: AppData
)

@Composable
private fun TemplateManager(store: TimeBricksStore, day: Long, onDateChange: (LocalDate) -> Unit, onClose: () -> Unit,
    onDayChanged: () -> Unit) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    var chooser by remember { mutableStateOf<String?>(null) }
    var newMenu by remember { mutableStateOf(false) }
    var pickSource by remember { mutableStateOf(false) }
    var applyId by remember { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var request by remember { mutableStateOf<TemplateRequest?>(null) }
    val data = store.data
    val defaultId = data.defaultOn(LocalDate.now().toEpochDay())?.templateId
    fun ask(kind: String, id: String? = null, target: Long = day) {
        request = TemplateRequest(kind, id, target, LocalDate.now().toEpochDay(), store.data); openId = null
    }
    fun edit(id: String?, source: Long? = null) { sourceDay = source; editingId = id ?: ""; openId = null }
    fun choose(id: String?) {
        store.change("默认安排已更新", { it.setDefaultTemplate(id, LocalDate.now().toEpochDay()) }, true, { chooser = null })
    }
    fun leave() { if (!store.busy) { if (showArchived) { showArchived = false; openId = null } else onClose() } }
    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { leave() }
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.fillMaxSize().padding(horizontal = BricksDimensions.pageInset)) {
                    BricksHeader(if (showArchived) "已归档" else "模板管理", !store.busy, { leave() }, action = {
                        if (!showArchived && data.templates.any { it.archived }) TextButton(enabled = !store.busy, onClick = { showArchived = true; openId = null }) { Text("归档") }
                    })
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("template-manager"), verticalArrangement = Arrangement.spacedBy(BricksDimensions.gap),
                        contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (!showArchived) item(key = "options") {
                            SettingsGroup {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("默认空白", Modifier.weight(1f), color = Primary, fontSize = 15.sp)
                                    Switch(checked = defaultId == null, enabled = !store.busy, modifier = Modifier.testTag("default-blank"),
                                        onCheckedChange = { if (it) choose(null) else chooser = "default" })
                                }
                                if (day in data.createdEvents) SettingsLink("恢复继承", enabled = !store.busy) { ask("inherit") }
                                if (data.templates.any { !it.archived }) SettingsLink("应用模板", enabled = !store.busy) { chooser = "apply" }
                            }
                        }
                        item(key = "heading") { Text(if (showArchived) "归档模板" else "模板列表", color = Secondary, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 4.dp)) }
                        itemsIndexed(data.templates.filter { it.archived == showArchived }, key = { _, value -> value.id }) { _, value ->
                            CompactManagementRow("template-${value.id}", value.name, selected = value.id == defaultId,
                                enabled = !store.busy, open = openId == value.id, onOpen = { openId = if (it) value.id else null },
                                onClick = { if (value.archived) edit(value.id) else choose(value.id) }, actions = listOf(
                                    RowAction("编辑") { edit(value.id) },
                                    RowAction(if (value.archived) "恢复" else "归档") {
                                        if (!value.archived && value.id == defaultId) ask("archive", value.id)
                                        else store.change(if (value.archived) "模板已恢复" else "模板已归档", {
                                            it.archiveTemplate(value.id, !value.archived, LocalDate.now().toEpochDay())
                                        }, true)
                                    }, RowAction("删除", true) { ask("delete", value.id) }))
                        }
                        if (!showArchived) item(key = "new") { SettingsGroup { SettingsLink("新建模板", enabled = !store.busy) { newMenu = true } } }
                    }
                }
                StoreNotice(store, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
    }
    if (newMenu) CompactBricksDialog(onDismissRequest = { newMenu = false }, title = { Text("新建模板") }, text = {
        Column {
            ValueRow("从某日保存", "", !store.busy) { newMenu = false; pickSource = true }
            ValueRow("新建空白", "", !store.busy) { newMenu = false; edit(null) }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { newMenu = false }) { Text("取消") } })
    if (pickSource) DateWheelDialog(LocalDate.ofEpochDay(day), { pickSource = false }) { pickSource = false; edit(null, it.toEpochDay()) }
    chooser?.let { mode -> BricksDialog(onDismissRequest = { chooser = null }, title = { Text(if (mode == "default") "默认模板" else "应用模板") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            data.templates.filterNot { it.archived }.forEach { template -> SettingsLink(template.name, enabled = !store.busy) {
                if (mode == "default") choose(template.id) else { chooser = null; applyId = template.id }
            } }
            SettingsLink("新建模板", enabled = !store.busy) { chooser = null; newMenu = true }
        }
    }, confirmButton = {}, dismissButton = { TextButton(enabled = !store.busy, onClick = { chooser = null }) { Text("取消") } }) }
    applyId?.let { id -> DateWheelDialog(LocalDate.ofEpochDay(day), { applyId = null }) { applyId = null; ask("apply", id, it.toEpochDay()) } }
    editingId?.let { id -> key(id, sourceDay) {
        val draft = remember { TemplateDraftRequest(id.takeIf { it.isNotEmpty() }, sourceDay, store.data) }
        TemplateDraftEditor(store, draft) { editingId = null }
    } }
    request?.let { pending -> TemplateConfirmDialog(pending, store.busy, store.notice, { request = null }) {
        store.change("操作已保存", { current ->
            require(current == pending.snapshot) { "数据已变化，请取消后重新操作" }
            require(LocalDate.now().toEpochDay() == pending.today) { "日期已变化，请取消后重试" }
            when (pending.kind) {
                "inherit" -> current.restoreDayInheritance(pending.day, true)
                "apply" -> current.applyTemplate(pending.day, pending.templateId!!, true)
                "archive" -> current.archiveTemplate(pending.templateId!!, true, pending.today)
                "delete" -> current.deleteTemplate(pending.templateId!!, pending.today, true)
                else -> current
            }
        }, true, {
            if (pending.kind in listOf("apply", "inherit")) { onDateChange(LocalDate.ofEpochDay(pending.day)); onDayChanged() }
            request = null
        })
    } }
}

@Composable
private fun ImportTemplateDialog(store: TimeBricksStore, day: Long, snapshot: AppData, onClose: () -> Unit) {
    val initialNotice = remember { store.notice }
    val templates = snapshot.templates.filterNot { it.archived }
    val hasEvents = snapshot.eventsOn(day).isNotEmpty()
    TemplateImportPicker(templates.map { it.id to it.name }, hasEvents, store.busy,
        if (!store.busy && store.notice != initialNotice) store.notice else "", onClose) { id, replace ->
        store.change("模板已导入 ${LocalDate.ofEpochDay(day)}", {
            require(it == snapshot) { "数据已变化，请取消后重试" }
            it.importTemplate(day, id, replace)
        }, trackUndo = true, onSaved = onClose)
    }
}

// UI-only picker: the caller owns storage and the import transaction.
@Composable
internal fun TemplateImportPicker(templates: List<Pair<String, String>>, hasEvents: Boolean, busy: Boolean,
    notice: String, onClose: () -> Unit, onImport: (String, Boolean) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    fun save(replace: Boolean) { selected?.let { onImport(it, replace) } }
    CompactBricksDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("导入模板") }, text = {
        // CompactBricksDialog already scrolls its body. A second unbounded vertical
        // scroller here throws during measurement, including with an empty list.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (templates.isEmpty()) Text("暂无模板")
            templates.forEach { (id, name) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected == id, enabled = !busy,
                    role = Role.RadioButton) { selected = id }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (selected == id) SelectedMark() else Spacer(Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(name, modifier = Modifier.weight(1f))
                }
            }
            if (!busy && notice.isNotBlank()) Text(notice, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = selected != null && !busy, onClick = { save(false) }) { Text(if (hasEvents) "追加" else "导入") }
    }, dismissButton = {
        Row {
            if (hasEvents) TextButton(enabled = selected != null && !busy, onClick = { save(true) }) { Text("替换") }
            TextButton(enabled = !busy, onClick = onClose) { Text("取消") }
        }
    })
}

private data class TemplateDraftRequest(val id: String?, val sourceDay: Long?, val snapshot: AppData)

@Composable
private fun TemplateDraftEditor(store: TimeBricksStore, request: TemplateDraftRequest, onClose: () -> Unit) {
    val old = request.snapshot.templates.firstOrNull { it.id == request.id }
    val originalJson = rememberSaveable { eventsToJson(request.snapshot.templateDraftEvents(request.id, request.sourceDay)).toString() }
    val original = remember(originalJson) { eventsFromJson(JSONArray(originalJson)) }
    var name by rememberSaveable { mutableStateOf(old?.name.orEmpty()) }
    var draftJson by rememberSaveable { mutableStateOf(eventsToJson(original).toString()) }
    val events = remember(draftJson) { eventsFromJson(JSONArray(draftJson)) }
    var mainId by rememberSaveable { mutableStateOf(request.snapshot.categories.firstOrNull { !it.archived }?.id.orEmpty()) }
    var childId by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var cycle by remember { mutableStateOf<OverlapRegion?>(null) }
    var precise by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf<Long?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val initialNotice = remember { store.notice }
    val undo = remember { mutableStateListOf<String>() }; val redo = remember { mutableStateListOf<String>() }
    val categories = request.snapshot.categories
    val active = categories.firstOrNull { it.id == mainId && !it.archived }
    val dirty = name != old?.name.orEmpty() || events != original
    fun commit(next: List<DemoEvent>) {
        if (store.busy) return
        val json = eventsToJson(next).toString()
        if (json != draftJson) { undo.add(draftJson); if (undo.size > 50) undo.removeAt(0); redo.clear(); draftJson = json; cycle = null }
    }
    fun leave() { if (!store.busy) { if (dirty) confirmDiscard = true else onClose() } }
    fun save(today: Long) {
        try {
            val next = request.snapshot.saveTemplateDraft(request.id, name.trim(), events, today)
            error = ""
            store.change("模板已保存", {
                require(it == request.snapshot) { "数据已变化，请取消后重新打开" }
                require(LocalDate.now().toEpochDay() == today) { "日期已变化，请重新保存" }; next
            }, true, onClose)
        } catch (failure: IllegalArgumentException) { error = failure.message ?: "请检查模板内容"; confirmSave = null }
    }
    LaunchedEffect(events, selectedId) { if (events.none { it.id == selectedId }) selectedId = null }
    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { leave() }
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = BricksDimensions.pageInset)) {
                EditorHeader(if (old == null) "新建模板" else "编辑模板", !store.busy, { leave() }) {
                    if (!validTemplateName(name.trim())) error = "请输入模板名称，不能含换行"
                    else if (old != null && old.events != events && request.snapshot.defaultHistory.lastOrNull()?.templateId == old.id)
                        confirmSave = LocalDate.now().toEpochDay()
                    else save(LocalDate.now().toEpochDay())
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewport = maxHeight
                    Layout(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = {
                        Column {
                            OutlinedTextField(name, { name = it }, label = { Text("模板名称") }, enabled = !store.busy,
                                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("template-draft-name"))
                            PlanningSummary(events, undo.isNotEmpty() && !store.busy, redo.isNotEmpty() && !store.busy,
                                { if (undo.isNotEmpty()) { redo.add(draftJson); draftJson = undo.removeAt(undo.lastIndex); cycle = null } },
                                { if (redo.isNotEmpty()) { undo.add(draftJson); draftJson = redo.removeAt(redo.lastIndex); cycle = null } })
                            CategoryBar(categories, active, childId, { id ->
                                mainId = id; childId = ""
                                selectedId?.let { selected -> commit(events.map { if (it.id == selected) it.copy(categoryId = id, child = "") else it }) }
                            }, { child ->
                                childId = child
                                selectedId?.let { selected -> if (active != null) commit(events.map { if (it.id == selected) it.copy(categoryId = active.id, child = child) else it }) }
                            }, null, null, request.snapshot.settings)
                            Spacer(Modifier.height(8.dp))
                        }
                        TimeMatrix(events, categories, active, childId, { commit(events + it) }, selectedId, { selectedId = it },
                            { id, start, end -> commit(events.map { if (it.id == id) it.copy(start = start, end = end).storedFromView(0, 0).copy(dayOffset = 0) else it }) },
                            request.sourceDay ?: 0L, cycle, { cycle = it }, store.busy)
                    }) { measurables, constraints ->
                        val header = measurables[0].measure(Constraints(maxWidth = constraints.maxWidth))
                        val height = (viewport.roundToPx() - header.height - 8.dp.roundToPx())
                            .coerceIn(8 * 24.dp.roundToPx() + 7 * 5.dp.roundToPx(), 8 * 44.dp.roundToPx() + 7 * 5.dp.roundToPx())
                        val matrix = measurables[1].measure(Constraints.fixed(constraints.maxWidth, height))
                        layout(constraints.maxWidth, header.height + matrix.height + 8.dp.roundToPx()) { header.placeRelative(0, 0); matrix.placeRelative(0, header.height) }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (!store.busy && store.notice != initialNotice) Text(store.notice, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                EventToolbar(events.firstOrNull { it.id == selectedId }, categories,
                    { id -> commit(events.filterNot { it.id == id }); selectedId = null },
                    { id, start, end -> commit(events.map { if (it.id == id) it.copy(start = start, end = end).storedFromView(0, 0).copy(dayOffset = 0) else it }) },
                    { selectedId = it; if (it == null) cycle = null },
                    cycle?.takeIf { selectedId in it.ids }?.let { "${it.ids.indexOf(selectedId) + 1}/${it.ids.size}" },
                    { cycle = null }, categories.any { !it.archived }, { precise = true })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (precise) PreciseTimeDialog("新建事件", 540, 600, categories, active?.id.orEmpty(), childId,
        onDismiss = { precise = false }, onSave = { start, end, parent, child -> commit(events + DemoEvent(start, end, parent, child)); precise = false })
    confirmSave?.let { today -> BricksDialog(onDismissRequest = { if (!store.busy) confirmSave = null }, title = { Text("更新默认模板") },
        text = { Text("从 ${LocalDate.ofEpochDay(today)} 起，未编辑日期继承新模板。历史版本、独立日期和重复事件保留。") },
        confirmButton = { TextButton(enabled = !store.busy, onClick = { save(today) }) { Text("保存") } },
        dismissButton = { TextButton(enabled = !store.busy, onClick = { confirmSave = null }) { Text("取消") } }) }
    if (confirmDiscard) BricksDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃修改？") },
        text = { Text("未保存的模板草稿将被丢弃。") }, confirmButton = { TextButton(onClick = onClose) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
}

private fun hasArchivedLabels(events: List<DemoEvent>, categories: List<Category>): Boolean = events.any { event ->
    categories.firstOrNull { it.id == event.categoryId }?.let { category ->
        category.archived || category.children.any { it.id == event.child && it.archived }
    } == true
}

@Composable
private fun TemplatePreviewRow(event: DemoEvent, categories: List<Category>) {
    val category = categories.firstOrNull { it.id == event.categoryId }
    val child = category?.children?.firstOrNull { it.id == event.child }
    val archived = category?.archived == true || child?.archived == true
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(BricksDimensions.medium)).background(TrackBg).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(child?.color ?: category?.color ?: Secondary))
        Column(Modifier.weight(1f)) {
            Text((category?.name ?: "未知分类") + if (child != null) "·${child.name}" else "", color = Primary, fontSize = 14.sp)
            Text(durationLabel(event.end - event.start) + if (archived) " · 已归档" else "", color = Secondary, fontSize = 12.sp)
        }
        Text("${clockMinute(event.start)}–${clockMinute(event.end)}", color = Primary, fontSize = 12.sp)
    }
}

@Composable
private fun TemplatePreview(events: List<DemoEvent>, categories: List<Category>) {
    Text("${events.size}项 · 计划${durationLabel(events.sumOf { it.end - it.start })} · 覆盖${durationLabel(scheduledMinutes(events))}", color = Secondary, fontSize = 12.sp)
    events.sortedBy { it.start }.forEach { TemplatePreviewRow(it, categories) }
    if (events.isEmpty()) Text("空白安排", color = Secondary, fontSize = 13.sp)
    if (hasArchivedLabels(events, categories)) Text("已归档分类会保留原标签，不会丢弃事件。", color = Secondary, fontSize = 12.sp)
}

@Composable
private fun TemplateConfirmDialog(request: TemplateRequest, busy: Boolean, notice: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val initialNotice = remember(request) { notice }
    val data = request.snapshot
    val template = data.templates.firstOrNull { it.id == request.templateId }
    val isDefault = template != null && data.defaultOn(request.today)?.templateId == template.id
    val title = when (request.kind) {
        "blankDay" -> "清空当日安排"; "inherit" -> "恢复继承"; "apply" -> "应用模板"
        "update" -> "更新模板"; "archive" -> "归档模板"; else -> "删除模板"
    }
    BricksDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (request.kind) {
                "blankDay" -> Text("清空 ${LocalDate.ofEpochDay(request.day)} 的基础安排，重复事件与单次例外保留。模板本身不变，当日保持独立空白，可撤销。")
                "inherit" -> Text("移除 ${LocalDate.ofEpochDay(request.day)} 的独立安排，恢复该日默认模板。重复事件与单次例外保留，可撤销。")
                "delete", "archive" -> Text("${if (request.kind == "delete") "删除" else "归档"}「${template!!.name}」？" +
                    if (isDefault) "从今天起改为默认空白，过去日程和独立日期保留。" else "历史版本和独立日期保留。")
                "apply" -> {
                    Text("替换 ${LocalDate.ofEpochDay(request.day)} 的基础安排。重复事件和单次例外保留，可撤销。")
                    Text("替换后", color = Primary)
                    TemplatePreview(template!!.events, data.categories)
                }
                "update" -> {
                    Text("用 ${LocalDate.ofEpochDay(request.day)} 的基础安排更新「${template!!.name}」。" +
                        if (isDefault) "从今天起影响未编辑日期，过去及独立日期不变。" else "来源日期不变。")
                    Text("更新后", color = Primary)
                    TemplatePreview(data.baseEventsOn(request.day), data.categories)
                }
            }
            if (!busy && notice != initialNotice) Text(notice, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !busy, modifier = Modifier.testTag("template-confirm"), onClick = onConfirm) { Text("确认") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun CompactBricksDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth()
            .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(180).dp),
            shape = RoundedCornerShape(BricksDimensions.pill * 1.5f), color = NavBg, contentColor = Primary) {
            Column(Modifier.padding(16.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp)) { title() }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) { dismissButton?.invoke(); confirmButton() }
            }
        }
    }
}

@Composable
private fun CompactCategoryChoice(categories: List<Category>, categoryId: String, childId: String, enabled: Boolean,
    onChange: (String, String) -> Unit) {
    val category = categories.firstOrNull { it.id == categoryId }
    val child = category?.children?.firstOrNull { it.id == childId }
    var choosing by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("分类", color = Primary, fontSize = 14.sp)
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(BricksDimensions.small))
                .clickable(enabled = enabled, role = Role.Button) { choosing = "parent" }
                .semantics { contentDescription = "主分类 ${category?.name ?: "请选择"}" }, verticalAlignment = Alignment.CenterVertically) {
                Text(category?.name ?: "请选择", Modifier.weight(1f), color = Primary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChevronIcon()
            }
            DropdownMenu(expanded = choosing == "parent", onDismissRequest = { choosing = null }) {
                categories.filter { !it.archived || it.id == categoryId }.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name + if (c.archived) "（归档）" else "") }, onClick = { onChange(c.id, ""); choosing = null })
                }
            }
        }
        if (category?.children?.isNotEmpty() == true) {
            Text("·", color = Secondary)
            Box(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(BricksDimensions.small))
                    .clickable(enabled = enabled, role = Role.Button) { choosing = "child" }
                    .semantics { contentDescription = "子分类 ${child?.name ?: "仅主分类"}" }, verticalAlignment = Alignment.CenterVertically) {
                    Text(child?.name ?: "仅主分类", Modifier.weight(1f), color = Secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ChevronIcon()
                }
                DropdownMenu(expanded = choosing == "child", onDismissRequest = { choosing = null }) {
                    DropdownMenuItem(text = { Text("仅主分类") }, onClick = { onChange(category.id, ""); choosing = null })
                    category.children.filter { !it.archived || it.id == childId }.forEach { c ->
                        DropdownMenuItem(text = { Text(c.name + if (c.archived) "（归档）" else "") }, onClick = { onChange(category.id, c.id); choosing = null })
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleExportControls(store: TimeBricksStore, content: @Composable ((Long) -> Unit) -> Unit) {
    var choosingDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val txt = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"), store::saveScheduleDocument)
    val png = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/png"), store::saveScheduleDocument)
    val ics = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/calendar"), store::saveScheduleDocument)
    val systemDark = isSystemInDarkTheme()
    content { if (!store.busy) choosingDay = it }
    choosingDay?.let { selected -> key(selected) {
        var first by rememberSaveable { mutableLongStateOf(selected) }
        var last by rememberSaveable { mutableLongStateOf(selected) }
        var formatName by rememberSaveable { mutableStateOf("TXT") }
        var error by remember { mutableStateOf("") }
        val format = ScheduleExportFormat.valueOf(formatName)
        CompactBricksDialog(onDismissRequest = { if (!store.busy) choosingDay = null }, title = { Text("导出日程") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DateValueRow("开始日期", first, !store.busy) { first = it; if (last < first) last = first; error = "" }
                DateValueRow("结束日期", last, !store.busy) { last = it; error = "" }
                SingleChoice(ScheduleExportFormat.entries.map { it.name to it.label }, formatName, !store.busy) { formatName = it; error = "" }
                if (format == ScheduleExportFormat.ICS) Text("仅导出所选区间，保留重复规则及单次例外；按本地钟点导入。跨版本规则会分段。", color = Secondary, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(enabled = !store.busy, onClick = {
            try {
                val range = ScheduleExportRange(first, last)
                store.prepareScheduleExport(range, format, store.data.settings.usesDarkTheme(systemDark)) { document ->
                    try { when (document.format) {
                        ScheduleExportFormat.TXT -> txt.launch(document.filename)
                        ScheduleExportFormat.PNG -> png.launch(document.filename)
                        ScheduleExportFormat.ICS -> ics.launch(document.filename)
                    } } catch (_: Exception) { store.schedulePickerFailed() }
                }
                choosingDay = null
            } catch (failure: IllegalArgumentException) { error = failure.message ?: "请检查日期" }
        }) { Text("导出") } }, dismissButton = { TextButton(enabled = !store.busy, onClick = { choosingDay = null }) { Text("取消") } })
    } }
    if (store.scheduleExportProgress.isNotEmpty()) BricksDialog(onDismissRequest = {}, title = { Text(store.scheduleExportProgress) },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    if (store.scheduleExportMessage.isNotEmpty()) BricksDialog(onDismissRequest = store::dismissScheduleExportMessage,
        title = { Text("导出日程") }, text = { Text(store.scheduleExportMessage) },
        confirmButton = { TextButton(onClick = store::dismissScheduleExportMessage) { Text("确定") } })
}

private fun backupError(error: Exception): String = when (error) {
    is SecurityException -> "文件访问权限已失效，请重新选择"
    is java.io.FileNotFoundException -> "文件不存在或存储位置不可用"
    is java.io.IOException, is android.system.ErrnoException -> "存储读写失败，请检查剩余空间和存储位置"
    else -> (error.message ?: "文件格式无效").take(120)
}

@Composable
private fun BackupControls(store: TimeBricksStore, content: @Composable (() -> Unit, () -> Unit) -> Unit) {
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"), store::exportTo)
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), store::importFrom)
    val export: () -> Unit = {
        store.prepareExport {
            try { exporter.launch("TimeBricks-${java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json") }
            catch (_: Exception) { store.pickerFailed() }
        }
    }
    val import: () -> Unit = {
        if (store.beginImportSelection()) {
            // Some providers label .json as text/plain or application/octet-stream.
            // Accept any document, then validate its actual bytes and complete schema.
            try { importer.launch(arrayOf("*/*")) } catch (_: Exception) { store.pickerFailed() }
        }
    }
    content(export, import)
    store.pendingRestore?.let { pending ->
        BricksDialog(onDismissRequest = store::cancelRestore, title = { Text("替换本机全部数据？") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("完整替换，不合并。所有日期、分类及归档、模板历史、重复规则及单次例外、颜色和外观设置都会被替换；撤销与重做记录将清空。此操作不可撤销。")
                Text("本机当前\n${pending.before.backupSummary()}", fontSize = 13.sp)
                Text("导入后 · JSON v${pending.candidate.sourceVersion}\n${pending.candidate.data.backupSummary()}", fontSize = 13.sp)
                pending.candidate.generatedAt?.let { Text("备份生成时间（UTC）\n$it", fontSize = 12.sp, color = Secondary) }
                TextButton(enabled = !store.busy, onClick = export) { Text("先备份本机") }
            }
        }, confirmButton = { TextButton(enabled = !store.busy, onClick = store::confirmRestore,
            modifier = Modifier.testTag("backup-confirm")) { Text("替换并恢复", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !store.busy, onClick = store::cancelRestore) { Text("取消") } })
    }
    if (store.backupProgress.isNotEmpty()) BricksDialog(onDismissRequest = {}, title = { Text(store.backupProgress) },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    if (store.backupMessage.isNotEmpty()) BricksDialog(onDismissRequest = store::dismissBackupMessage,
        title = { Text("数据管理") }, text = { Text(store.backupMessage) },
        confirmButton = { TextButton(onClick = store::dismissBackupMessage) { Text("确定") } })
}

@Composable
private fun SettingsPage(store: TimeBricksStore, onExport: () -> Unit, onImport: () -> Unit, onManageCategories: () -> Unit, onManageTemplates: () -> Unit, onManageRepeats: () -> Unit) {
    var detail by rememberSaveable { mutableStateOf("") }
    var confirmClearAll by remember { mutableStateOf(false) }
    val settings = store.data.settings
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val autoBackup by AutoBackupSystem.status.collectAsState()
    val pickBackupFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) AutoBackupSystem.enable(application, uri) }
    val openBackupFolder: () -> Unit = {
        try { pickBackupFolder.launch(null) }
        catch (_: Exception) { /* The switch stays off if the document picker is unavailable. */ }
    }
    fun backFromDetail() {
        if (!store.busy) detail = when (detail) {
            "statusSchedule" -> "reminders"
            "scheduleAccess" -> "backup"
            else -> ""
        }
    }
    BackHandler(enabled = detail.isNotEmpty()) { backFromDetail() }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = BricksDimensions.pageInset)) {
        if (detail.isEmpty()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("设置", color = Primary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        } else BricksHeader(when (detail) { "appearance" -> "外观主题"; "reminders" -> "通知提醒"; "statusSchedule" -> "状态栏日程"; "backup" -> "数据管理"; "scheduleAccess" -> "日程读取授权"; else -> "分类显示" }, !store.busy, { backFromDetail() })
        key(detail) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(BricksDimensions.gap * 2)) {
                when (detail) {
                    "reminders" -> ReminderSettings(store, onStatusSchedule = { detail = "statusSchedule" })
                    "statusSchedule" -> StatusScheduleSettings(store)
                    "scheduleAccess" -> ScheduleAccessSettings()
                    "backup" -> {
                        SettingsGroup {
                            Row(Modifier.fillMaxWidth().heightIn(min = BricksDimensions.listRow)
                                .clickable(enabled = !store.busy, role = Role.Switch) {
                                    if (autoBackup.enabled) AutoBackupSystem.disable(application) else openBackupFolder()
                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("自动本地备份", color = Primary, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Switch(checked = autoBackup.enabled, enabled = !store.busy,
                                    onCheckedChange = { enabled ->
                                        if (enabled) openBackupFolder() else AutoBackupSystem.disable(application)
                                    })
                            }
                            if (autoBackup.enabled) {
                                SettingsLink("备份位置", subtitle = autoBackup.folder.ifBlank { "已选择文件夹" },
                                    enabled = !store.busy, onClick = openBackupFolder)
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("保留备份", color = Primary, fontSize = 14.sp)
                                    Text("最近7份", color = Secondary, fontSize = 14.sp)
                                }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("最近备份", color = Primary, fontSize = 14.sp)
                                    val last = autoBackup.lastBackup.takeIf { it.isNotBlank() }?.let {
                                        runCatching { java.time.ZonedDateTime.ofInstant(Instant.parse(it), java.time.ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrNull()
                                    } ?: "尚无"
                                    Text(last, color = Secondary, fontSize = 14.sp)
                                }
                            }
                            if (autoBackup.error.isNotBlank()) Text(autoBackup.error, color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        SettingsGroup {
                            SettingsLink("导出备份", enabled = !store.busy, onClick = onExport)
                            SettingsLink("导入备份", enabled = !store.busy, onClick = onImport)
                            SettingsLink("日程读取授权", subtitle = "查看授权状态与已授权应用", enabled = !store.busy) {
                                detail = "scheduleAccess"
                            }
                        }
                        SettingsGroup {
                            SettingsLink("清空所有日程", enabled = !store.busy && store.loaded,
                                onClick = { confirmClearAll = true })
                        }
                    }
                    "appearance" -> SettingsGroup {
                        Column(Modifier.padding(BricksDimensions.pageInset)) { AppearanceSettings(store) }
                    }
                    "categories" -> SettingsGroup {
                        Column(Modifier.padding(BricksDimensions.pageInset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RowSetting("主分类", settings.mainRows, !store.busy) { rows ->
                                store.change("显示方式已保存", { it.copy(settings = it.settings.copy(mainRows = rows)) })
                            }
                            RowSetting("子分类", settings.childRows, !store.busy) { rows ->
                                store.change("显示方式已保存", { it.copy(settings = it.settings.copy(childRows = rows)) })
                            }
                        }
                    }
                    else -> {
                        SettingsGroup {
                            SettingsLink("外观主题") { detail = "appearance" }
                            SettingsLink("分类显示") { detail = "categories" }
                        }
                        SettingsGroup {
                            SettingsLink("分类管理", onClick = onManageCategories)
                            SettingsLink("模板管理", onClick = onManageTemplates)
                            SettingsLink("重复事件", onClick = onManageRepeats)
                        }
                        SettingsGroup { SettingsLink("通知提醒") { detail = "reminders" } }
                        SettingsGroup { SettingsLink("数据管理", enabled = !store.busy) { detail = "backup" } }
                    }
                }
                if (store.notice.startsWith("保存失败")) Text(store.notice, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (confirmClearAll) BricksDialog(
        onDismissRequest = { if (!store.busy) confirmClearAll = false },
        title = { Text("清空所有日程？") },
        text = { Text("将清空全部日期的安排、默认模板继承及重复事件规则和单次例外。保留分类、已保存的模板、外观、通知和备份设置。此操作不能跨重启撤销，建议先导出备份。") },
        confirmButton = {
            TextButton(enabled = !store.busy, onClick = {
                store.change("所有日程已清空", { it.clearAllSchedules() }, trackUndo = true,
                    onSaved = { confirmClearAll = false })
            }) { Text("清空", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(enabled = !store.busy, onClick = { confirmClearAll = false }) { Text("取消") }
        }
    )
}

@Composable
private fun ScheduleAccessSettings() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var entries by remember { mutableStateOf<List<ScheduleAccessEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        error = null
        entries = null
        try { entries = withContext(Dispatchers.IO) { readScheduleAccessEntries(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "暂时无法读取授权状态，请重试。" }
    }
    ScheduleAccessContent(entries, error, notice, onRefresh = { refresh++ }, onRevoke = { entry ->
        try {
            ScheduleAccess.revoke(context, entry.packageName)
            notice = if (entry.status == ScheduleReadStatus.SIGNATURE)
                "已撤销手动授权；同签名读取权限仍然有效。" else "已撤销手动授权并移除记录。"
        } catch (_: Exception) { notice = "未能完成撤销，请刷新状态后重试。" }
        refresh++
    })
}

/** Uses the same settings surfaces and appearance values as every other settings detail. */
@Composable
internal fun ScheduleAccessContent(entries: List<ScheduleAccessEntry>?, error: String?, notice: String?,
    onRefresh: () -> Unit, onRevoke: (ScheduleAccessEntry) -> Unit) {
    var pendingPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = entries?.firstOrNull { it.packageName == pendingPackage && it.hasManualGrant }
    // Do not keep a confirmation alive if a refresh removed the record or changed its access type.
    LaunchedEffect(entries) { if (pending == null) pendingPackage = null }
    SettingsGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前授权状态", color = Primary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(when {
                error != null -> error
                entries == null -> "正在检查授权状态…"
                entries.isEmpty() -> "暂无可见的授权应用"
                else -> {
                    val readable = entries.count { it.status in setOf(ScheduleReadStatus.GRANTED, ScheduleReadStatus.PARTIAL, ScheduleReadStatus.SIGNATURE) }
                    val pendingCount = entries.count { it.status in setOf(ScheduleReadStatus.EXPIRED, ScheduleReadStatus.UNKNOWN) }
                    "${readable} 个应用可读取日程 · ${pendingCount} 个记录待确认或重新授权"
                }
            }, color = if (error != null) MaterialTheme.colorScheme.error else Primary, fontSize = 14.sp)
            Text("仅限本机只读访问。授权记录不等于当前权限，状态以本次系统检查为准。",
                color = Secondary, fontSize = 12.sp)
            TextButton(onClick = onRefresh) { Text("刷新状态") }
            if (notice != null) Text(notice, color = Primary, fontSize = 13.sp)
        }
    }
    Text("应用与授权记录", color = Secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
    if (entries?.isEmpty() == true) SettingsGroup {
        ScheduleAccessInfo("尚无授权记录", "在 SelfPact 中发起读取时间积木日程的请求，确认授权后，返回此页查看。")
    }
    entries.orEmpty().forEach { entry ->
        key(entry.packageName) {
            SettingsGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.label, color = Primary, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    Text(entry.packageName, color = Secondary, fontSize = 12.sp)
                    Text(entry.status.title, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    Text(entry.status.explanation, color = Secondary, fontSize = 13.sp)
                    if (entry.hasManualGrant) TextButton(onClick = { pendingPackage = entry.packageName }) {
                        Text("撤销手动授权", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    SettingsGroup {
        ScheduleAccessInfo("SelfPact · 已适配", "支持读取时间积木日程。授权由 SelfPact 发起，本页用于查看和撤销；此说明不代表 SelfPact 已安装或已获授权。")
        ScheduleAccessInfo("授权范围与有效期", "允许读取全部日期的日程、分类和时间，不能新增、修改或删除日程。手动授权在设备重启后失效，需回到来源应用重新申请。撤销后不会删除对方此前已读取的数据。")
        ScheduleAccessInfo("同签名访问说明", "同签名且持有读取权限的应用可直接访问，不受手动授权撤销影响。列表仅展示系统允许查询到的应用；没有记录不代表不存在同签名访问。")
    }
    Text("扩展能力", color = Secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
    SettingsGroup {
        ScheduleAccessInfo("按日期或分类授权 · 暂不可用", "尚未实现，目前仅支持全部日程的只读授权。")
        ScheduleAccessInfo("自定义有效期与访问记录 · 暂不可用", "尚未实现，暂不提供到期时间设置或最近读取记录。")
        ScheduleAccessInfo("更多应用接入 · 暂不可用", "尚未提供接入配置入口，后续将在此扩展。")
    }
    if (pending != null) BricksDialog(onDismissRequest = { pendingPackage = null },
        title = { Text("撤销手动授权？") },
        text = { Text("${pending.label}\n${pending.packageName}\n\n" +
            if (pending.status == ScheduleReadStatus.SIGNATURE)
                "仅撤销手动授权并移除记录；此应用仍可通过同签名权限读取日程。"
            else "将撤销日程接口的手动读取权限并移除记录。再次使用时需从来源应用申请授权；已读取的数据不会被删除。") },
        confirmButton = { TextButton(onClick = { pendingPackage = null; onRevoke(pending) }) {
            Text("确认撤销", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { pendingPackage = null }) { Text("取消") } })
}

@Composable
private fun ScheduleAccessInfo(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(description, color = Secondary, fontSize = 13.sp)
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(BricksDimensions.pill)).background(NavBg), content = content)
}

@Composable
private fun SettingsLink(title: String, subtitle: String = "", enabled: Boolean = true, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = BricksDimensions.listRow)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = if (enabled) Primary else Secondary, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (enabled) ChevronIcon(Modifier.padding(start = 12.dp))
    }
}

private val themePresets = listOf("蓝灰" to "527F93", "苔绿" to "657E62", "藤紫" to "877398", "陶棕" to "9B715C")
private fun themeModeLabel(mode: String): String = when (mode) { "system" -> "跟随系统"; "light" -> "日间"; else -> "夜间" }

@Composable
private fun AppearanceSettings(store: TimeBricksStore) {
    val settings = store.data.settings
    val enabled = !store.busy
    var pickingColor by remember { mutableStateOf(false) }
    Text("深浅模式", color = Primary, fontSize = 14.sp)
    SingleChoice(listOf("system" to "跟随系统", "light" to "日间", "dark" to "夜间"), settings.themeMode, enabled) { mode ->
        store.change("外观已保存", { it.copy(settings = it.settings.copy(themeMode = mode, darkTheme = mode != "light")) })
    }
    Spacer(Modifier.height(4.dp))
    ColorValueRow("主题颜色", Color(parseThemeHex(settings.themeHex)!!), enabled) { pickingColor = true }
    if (pickingColor) ColorPickerDialog(Color(parseThemeHex(settings.themeHex)!!), settings.recentColors, { pickingColor = false }) { color ->
        store.change("主题颜色已保存", { it.copy(settings = it.settings.copy(themeHex = colorHex(color))).withRecentColor(colorHex(color)) },
            onSaved = { pickingColor = false })
    }
    AppearanceSlider("字号", settings.fontScale, .85f..1.3f, 8, enabled, { "${(it * 100).roundToInt()}%" }) { value ->
        store.change("字号已保存", { it.copy(settings = it.settings.copy(fontScale = value)) })
    }
    AppearanceSlider("疏密", settings.spacingScale, .85f..1.2f, 6, enabled, { "${(it * 100).roundToInt()}%" }) { value ->
        store.change("疏密已保存", { it.copy(settings = it.settings.copy(spacingScale = value)) })
    }
    AppearanceSlider("圆角", settings.cornerDp, 0f..28f, 13, enabled, { "${it.roundToInt()} dp" }) { value ->
        store.change("圆角已保存", { it.copy(settings = it.settings.copy(cornerDp = value)) })
    }
    AppearanceSlider("积木透明度", settings.eventOpacity, .3f..1f, 13, enabled,
        { "${(it * 100).roundToInt()}%" }) { value ->
        store.change("积木透明度已保存", { it.copy(settings = it.settings.copy(eventOpacity = value)) })
    }
    TextButton(enabled = enabled, onClick = {
        store.change("已恢复默认外观", { it.copy(settings = DisplaySettings(mainRows = it.settings.mainRows, childRows = it.settings.childRows)) })
    }) { Text("恢复默认") }
}

@Composable
private fun AppearanceSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    enabled: Boolean, format: (Float) -> String, onCommit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label · ${format(draft)}", color = Primary, fontSize = 14.sp)
        Slider(value = draft, onValueChange = { draft = it }, enabled = enabled, valueRange = range, steps = steps,
            onValueChangeFinished = { onCommit(draft) }, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SingleChoice(options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            val active = selected == value
            val scheme = MaterialTheme.colorScheme
            val shape = RoundedCornerShape(BricksDimensions.medium)
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(shape)
                .background(if (active) scheme.secondaryContainer else scheme.surfaceContainer)
                .border(1.dp, if (active) scheme.primary else scheme.outlineVariant, shape)
                .selectable(active, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                .padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, color = (if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
                    .copy(alpha = if (enabled) 1f else .38f), textAlign = TextAlign.Center, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun RowSetting(label: String, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Primary, fontSize = 14.sp)
        SingleChoice(listOf("1" to "一行", "2" to "两行"), selected.toString(), enabled) { onSelect(it.toInt()) }
    }
}


private data class RepeatScopeRequest(val day: Long, val id: String, val replacement: DemoEvent?, val snapshot: AppData)
private data class RepeatEditRequest(val ruleId: String?, val day: Long, val snapshot: AppData,
    val proposed: DemoEvent? = null, val action: String = "edit")
private fun repeatStatusLabel(status: String): String = when (status) {
    "paused" -> "已暂停"; "archived" -> "已归档"; "deleted" -> "已删除"; else -> "启用"
}
private fun repeatDaysLabel(days: Set<Int>): String = if (days.size == 7) "每天" else
    "每周" + days.sorted().joinToString("、") { listOf("一", "二", "三", "四", "五", "六", "日")[it - 1] }
private fun parseRepeatDay(value: String): Long? = runCatching { LocalDate.parse(value.trim()).toEpochDay() }.getOrNull()

@Composable
private fun RepeatScopeDialog(store: TimeBricksStore, request: RepeatScopeRequest, onClose: () -> Unit,
    onRule: (RepeatEditRequest) -> Unit) {
    val initialNotice = remember(request) { store.notice }
    val deleting = request.replacement == null
    val rule = request.snapshot.ruleForOccurrence(request.day, request.id) ?: return
    BricksDialog(onDismissRequest = { if (!store.busy) onClose() }, title = { Text(if (deleting) "删除重复事件" else "修改重复事件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${LocalDate.ofEpochDay(request.day)} · ${rule.versionOn(request.day)?.name.orEmpty()}")

                request.replacement?.let { Text("本次拟改为 ${LocalDate.ofEpochDay(request.day + it.dayOffset)} ${clockMinute(it.start)} 至 ${LocalDate.ofEpochDay(request.day + it.dayOffset + Math.floorDiv(it.end, 1440))} ${clockMinute(Math.floorMod(it.end, 1440))}") }
                if (request.replacement?.dayOffset?.let { it != 0 } == true)
                    Text("日期移动仅应用于这一次；批量调整日期请进入重复事件管理。", fontSize = 12.sp)
                if (!store.busy && store.notice != initialNotice) Text(store.notice, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(enabled = !store.busy, onClick = {
                store.change(if (deleting) "已跳过这一次" else "单次例外已保存", {
                    require(it == request.snapshot) { "数据已变化，请关闭后重新选择" }
                    it.setRepeatException(request.day, request.id, request.replacement)
                }, trackUndo = true, onSaved = onClose)
            }) { Text("仅此一次") }
        }, dismissButton = {
            Row {
                TextButton(enabled = !store.busy, onClick = onClose) { Text("取消") }
                TextButton(enabled = !store.busy && rule.versions.last().status != "deleted" && (request.replacement?.dayOffset ?: 0) == 0, onClick = {
                    onRule(RepeatEditRequest(rule.id, request.day, request.snapshot, request.replacement,
                        if (deleting) "deleted" else "edit"))
                }) { Text("从此日起") }
            }
        })
}

@Composable
private fun RepeatManager(store: TimeBricksStore, day: Long, onDateChange: (LocalDate) -> Unit, onClose: () -> Unit,
    onEdit: (RepeatEditRequest) -> Unit) {
    var showInactive by rememberSaveable { mutableStateOf(false) }
    var showExceptions by rememberSaveable { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    var restore by remember { mutableStateOf<RepeatException?>(null) }
    val data = store.data
    fun leave() { if (!store.busy) { if (showInactive) { showInactive = false; openId = null } else onClose() } }
    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { leave() }
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.fillMaxSize().padding(horizontal = BricksDimensions.pageInset)) {
                    BricksHeader(if (showInactive) "归档与历史" else "重复事件", !store.busy, { leave() }, action = {
                        if (!showInactive && data.repeatRules.any { it.versions.last().status in listOf("archived", "deleted") })
                            TextButton(enabled = !store.busy, onClick = { showInactive = true; openId = null }) { Text("归档") }
                    })
                    LazyColumn(Modifier.weight(1f).testTag("repeat-manager"), verticalArrangement = Arrangement.spacedBy(BricksDimensions.gap),
                        contentPadding = PaddingValues(bottom = 24.dp)) {
                        val visible = data.repeatRules.filter { (it.versions.last().status in listOf("archived", "deleted")) == showInactive }
                        itemsIndexed(visible, key = { _, rule -> "rule-${rule.id}" }) { _, rule ->
                            val v = rule.versions.last()
                            val event = v.event
                            val category = data.categories.firstOrNull { it.id == event?.categoryId }
                            val child = category?.children?.firstOrNull { it.id == event?.child }
                            val title = category?.name?.let { it + (child?.let { c -> "·${c.name}" } ?: "") } ?: "历史与例外"
                            val status = if (v.status == "active") "" else " · ${repeatStatusLabel(v.status)}"
                            val dates = "${LocalDate.ofEpochDay(v.startDay)}起" + (v.endDay?.let { " 至 ${LocalDate.ofEpochDay(it)}" } ?: "")
                            val subtitle = event?.let { "${clockMinute(it.start)}–${clockMinute(it.end)} · ${repeatDaysLabel(v.weekdays)}$status\n$dates" } ?: "分类内容已移除$status"
                            fun edit(action: String = "edit") { openId = null; onEdit(RepeatEditRequest(rule.id, day, store.data, action = action)) }
                            CompactManagementRow("repeat-rule-${rule.id}", title, subtitle, child?.color ?: category?.color,
                                enabled = !store.busy, open = openId == rule.id, onOpen = { openId = if (it) rule.id else null },
                                onClick = { edit(if (v.status == "deleted") "history" else "edit") },
                                actions = if (v.status == "deleted") emptyList() else listOf(
                                    RowAction("编辑") { edit() },
                                    RowAction(if (v.status == "archived") "恢复" else "归档") { edit(if (v.status == "archived") "active" else "archived") },
                                    RowAction("删除", true) { edit("deleted") }))
                        }
                        if (!showInactive) item(key = "new") {
                            SettingsGroup {
                                Box(Modifier.testTag("repeat-create")) {
                                    SettingsLink("新建重复事件", enabled = !store.busy) { onEdit(RepeatEditRequest(null, day, store.data)) }
                                }
                            }
                        }
                        item(key = "exceptions-toggle") {
                            SettingsGroup { SettingsLink(if (showExceptions) "收起例外" else "单次例外", enabled = !store.busy) { showExceptions = !showExceptions } }
                        }
                        if (showExceptions) {
                            item(key = "date") { DateNavigation(LocalDate.ofEpochDay(day), { if (!store.busy) onDateChange(it) }) }
                            val exceptions = data.repeatExceptions.filter { it.day == day }
                            if (exceptions.isEmpty()) item(key = "empty") { Text("没有例外", color = Secondary, modifier = Modifier.padding(16.dp)) }
                            itemsIndexed(exceptions, key = { _, ex -> "exception-${ex.ruleId}" }) { _, ex ->
                                val rule = data.repeatRules.first { it.id == ex.ruleId }
                                SettingsGroup { SettingsLink(rule.versionOn(day)?.name ?: rule.versions.last().name,
                                    if (ex.replacement == null) "已跳过" else "已修改 ${clockMinute(ex.replacement.start)}–${clockMinute(ex.replacement.end)}",
                                    enabled = !store.busy) { restore = ex } }
                            }
                        }
                    }
                }
                StoreNotice(store, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
    }
    restore?.let { ex ->
        BricksDialog(onDismissRequest = { if (!store.busy) restore = null }, title = { Text("恢复规则安排") },
            text = { Text("移除 ${LocalDate.ofEpochDay(ex.day)} 的单次例外。按当天规则重新计算；已停止或不在周期内的规则不生成事件。") },
            confirmButton = { TextButton(enabled = !store.busy, onClick = {
                store.change("单次例外已移除", { it.restoreRepeatException(ex.ruleId, ex.day) }, true, { restore = null })
            }) { Text("恢复") } }, dismissButton = { TextButton(enabled = !store.busy, onClick = { restore = null }) { Text("取消") } })
    }
}

// Status changes retain the original effective-date transaction and have one commit point.
@Composable
private fun RepeatStatusDialog(store: TimeBricksStore, request: RepeatEditRequest, onClose: () -> Unit) {
    val rule = request.snapshot.repeatRules.first { it.id == request.ruleId }
    val old = rule.versions.last()
    var from by rememberSaveable(request) { mutableLongStateOf(request.day) }
    var error by remember(request) { mutableStateOf("") }
    val initialNotice = remember(request) { store.notice }
    val label = when (request.action) { "active" -> "恢复"; "paused" -> "暂停"; "archived" -> "归档"; "history" -> "历史记录"; else -> "删除" }
    val readOnly = request.action == "history"
    BricksDialog(onDismissRequest = { if (!store.busy) onClose() }, title = { Text(label) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(old.name, color = Primary)
            if (readOnly) {
                rule.versions.forEach { v ->
                    Text("${LocalDate.ofEpochDay(v.fromDay)} · ${repeatStatusLabel(v.status)}\n" +
                        (v.event?.let { "${clockMinute(it.start)}–${clockMinute(it.end)} · ${repeatDaysLabel(v.weekdays)}" } ?: "分类内容已移除"))
                }
            } else {
                DateValueRow("生效日期", from, !store.busy) { from = it }
                Text(if (request.action == "active") "从此日起恢复周期及已有单次例外。此前历史不变。" else
                    "从此日起停止该规则的全部实例（含单次修改），保留此前历史及例外数据。" +
                        if (request.action == "deleted") "可在本次运行中撤销。" else "可恢复。")
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                if (!store.busy && store.notice != initialNotice) Text(store.notice, color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = {
        if (!readOnly) TextButton(enabled = !store.busy, modifier = Modifier.testTag("repeat-save"), onClick = {
            try {
                val fromDay = from
                val next = request.snapshot.setRepeatStatus(request.ruleId!!, fromDay, request.action)
                error = ""
                store.change("规则已${label}", {
                    require(it == request.snapshot) { "数据已变化，请取消后重试" }; next
                }, trackUndo = true, onSaved = onClose)
            } catch (failure: IllegalArgumentException) { error = failure.message ?: "请检查输入" }
        }) { Text("确认") }
    }, dismissButton = { TextButton(enabled = !store.busy, onClick = onClose) { Text(if (readOnly) "返回" else "取消") } })
}

@Composable
private fun RepeatEditor(store: TimeBricksStore, request: RepeatEditRequest, onClose: () -> Unit) {
    if (request.action != "edit") { RepeatStatusDialog(store, request, onClose); return }
    val old = request.snapshot.repeatRules.firstOrNull { it.id == request.ruleId }?.versions?.last()
    val seed = request.proposed ?: old?.event
    var name by rememberSaveable { mutableStateOf(old?.name ?: "重复安排") }
    var from by rememberSaveable { mutableLongStateOf(request.day) }
    var first by rememberSaveable { mutableLongStateOf(old?.startDay ?: request.day) }
    var last by rememberSaveable { mutableStateOf(old?.endDay) }
    var start by rememberSaveable { mutableIntStateOf(seed?.start ?: 540) }
    var end by rememberSaveable { mutableIntStateOf(seed?.end ?: 600) }
    var categoryId by rememberSaveable { mutableStateOf(seed?.categoryId ?: request.snapshot.categories.firstOrNull { !it.archived }?.id.orEmpty()) }
    var childId by rememberSaveable { mutableStateOf(seed?.child.orEmpty()) }
    var mask by rememberSaveable { mutableIntStateOf(weekdayMask(old?.weekdays ?: (1..7).toSet())) }
    var mode by rememberSaveable { mutableStateOf(if (mask == 127) "daily" else "weekly") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var statusRequest by remember { mutableStateOf<RepeatEditRequest?>(null) }
    var error by remember { mutableStateOf("") }
    val initialNotice = remember { store.notice }
    fun save() {
        try {
            val next = request.snapshot.saveRepeatRule(request.ruleId, RepeatVersion(if (old == null) first else from, 1, name.trim(),
                DemoEvent(start, end, categoryId, childId), first, last, if (mode == "daily") (1..7).toSet() else weekdaySet(mask), old?.status ?: "active"))
            error = ""
            store.change("重复规则已保存", { require(it == request.snapshot) { "数据已变化，请取消后重新打开" }; next }, true, onClose)
        } catch (failure: IllegalArgumentException) { error = failure.message ?: "请检查输入" }
    }
    Dialog(onDismissRequest = { if (!store.busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = BricksDimensions.pageInset)) {
                EditorHeader(if (old == null) "新建重复事件" else "编辑重复事件", !store.busy, onClose, ::save)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsGroup {
                        OutlinedTextField(name, { name = it }, enabled = !store.busy, label = { Text("名称") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("repeat-name"))
                        CategoryChoice(request.snapshot.categories, categoryId, childId, !store.busy) { parent, child -> categoryId = parent; childId = child }
                        TimeRangeRows(start, end, !store.busy, { value ->
                            val shift = Math.floorDiv(value, 1440)
                            first += shift; start = Math.floorMod(value, 1440); end -= shift * 1440
                        }, { end = it }, baseDay = first, allowStartDate = true)
                    }
                    SettingsGroup {
                        ValueRow("重复", if (mode == "daily") "每天" else repeatDaysLabel(weekdaySet(mask)), !store.busy) { expanded = !expanded }
                        if (expanded) Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            RepeatOptions(mode, { mode = it }, mask, { mask = it }, first, last, { last = it }, false, !store.busy)
                        } else ValueRow("有效日期", "${LocalDate.ofEpochDay(first)} 至 ${last?.let { LocalDate.ofEpochDay(it).toString() } ?: "不限"}", !store.busy) { expanded = true }
                    }
                    if (old != null) SettingsGroup {
                        DateValueRow("生效日期", from, !store.busy) { from = it }
                        Text("保留生效日前历史与单次例外。", color = Secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        SettingsLink(if (old.status == "active") "暂停" else "恢复", enabled = !store.busy) {
                            statusRequest = request.copy(day = from, action = if (old.status == "active") "paused" else "active")
                        }
                    }
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (!store.busy && store.notice != initialNotice) Text(store.notice, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
    statusRequest?.let { pending -> RepeatStatusDialog(store, pending) { statusRequest = null; if (store.data != request.snapshot) onClose() } }
}


@Composable
private fun EditorHeader(title: String, enabled: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = enabled, onClick = onCancel) { Text("取消") }
        Text(title, Modifier.weight(1f), color = Primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(enabled = enabled, onClick = onSave) { Text("保存") }
    }
}

@Composable
private fun ChevronIcon(modifier: Modifier = Modifier, color: Color = Secondary) {
    Canvas(modifier.size(20.dp)) {
        val path = Path().apply { moveTo(size.width * .38f, size.height * .25f); lineTo(size.width * .63f, size.height * .5f); lineTo(size.width * .38f, size.height * .75f) }
        drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun SelectedMark() {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp).semantics { contentDescription = "已选中" }) {
        drawCircle(ink.copy(alpha = .16f))
        val path = Path().apply { moveTo(size.width * .26f, size.height * .51f); lineTo(size.width * .43f, size.height * .67f); lineTo(size.width * .75f, size.height * .34f) }
        drawPath(path, ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun ValueRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(BricksDimensions.medium))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Primary, fontSize = 14.sp)
        Text(value, Modifier.weight(1f), color = Secondary, fontSize = 14.sp, textAlign = TextAlign.End)
        if (enabled) ChevronIcon()
    }
}

@Composable
private fun WheelColumn(values: List<Int>, value: Int, label: String, modifier: Modifier = Modifier,
    format: (Int) -> String = { it.toString().padStart(2, '0') }, onValue: (Int) -> Unit) {
    key(values) {
        val state = rememberLazyListState(initialFirstVisibleItemIndex = values.indexOf(value).coerceAtLeast(0))
        val scope = rememberCoroutineScope()
        val latestOnValue by rememberUpdatedState(onValue)
        val density = LocalDensity.current
        val rowHeight = with(density) { maxOf(44.dp, 22.sp.toDp() + 16.dp) }
        LaunchedEffect(state, values) {
            snapshotFlow { state.isScrollInProgress to state.layoutInfo }.collect { (moving, layout) ->
                if (layout.visibleItemsInfo.isNotEmpty()) {
                    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                    val nearest = layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }
                    nearest?.index?.let { values.getOrNull(it) }?.let(latestOnValue)
                }
            }
        }
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Secondary, fontSize = 12.sp)
            Box(Modifier.fillMaxWidth().height(rowHeight * 5)) {
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(rowHeight)
                    .clip(RoundedCornerShape(BricksDimensions.small)).background(Accent))
                LazyColumn(state = state, flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
                    modifier = Modifier.fillMaxSize().testTag("wheel-$label").semantics {
                        contentDescription = "$label ${format(value)}"
                        customActions = listOf(
                            CustomAccessibilityAction("增加$label") { scope.launch { state.animateScrollToItem((values.indexOf(value) + 1).coerceAtMost(values.lastIndex)) }; true },
                            CustomAccessibilityAction("减少$label") { scope.launch { state.animateScrollToItem((values.indexOf(value) - 1).coerceAtLeast(0)) }; true })
                    }, contentPadding = PaddingValues(vertical = rowHeight * 2)) {
                    itemsIndexed(values, key = { _, n -> n }) { index, number ->
                        Box(Modifier.fillMaxWidth().height(rowHeight).clickable {
                            scope.launch { state.animateScrollToItem(index) }
                        }, contentAlignment = Alignment.Center) {
                            Text(format(number), color = if (number == value) Primary else Secondary.copy(alpha = .65f),
                                fontSize = if (number == value) 21.sp else 17.sp, maxLines = 1,
                                fontWeight = if (number == value) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeWheelDialog(initial: Int, initialDate: LocalDate, dateEnabled: Boolean = true,
    onDismiss: () -> Unit, onSave: (LocalDate, Int) -> Unit) {
    var hour by rememberSaveable { mutableIntStateOf(Math.floorMod(initial, 1440) / 60) }
    var minute by rememberSaveable { mutableIntStateOf(Math.floorMod(initial, 1440) % 60) }
    var epoch by rememberSaveable { mutableLongStateOf(initialDate.toEpochDay()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(epoch)
    CompactBricksDialog(onDismissRequest = onDismiss, title = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选择时间", fontSize = 18.sp)
            Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(TrackBg)
                .clickable(enabled = dateEnabled) { pickingDate = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center) {
                Text("${date.year}年${date.monthValue}月${date.dayOfMonth}日", fontSize = 14.sp, maxLines = 1)
            }
        }
    }, text = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WheelColumn((0..23).toList(), hour, "时", Modifier.weight(1f)) { hour = it }
            WheelColumn((0..59).toList(), minute, "分", Modifier.weight(1f)) { minute = it }
        }
    }, confirmButton = { TextButton(onClick = { onSave(date, hour * 60 + minute) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if (pickingDate) DateWheelDialog(date, { pickingDate = false }) { epoch = it.toEpochDay(); pickingDate = false }
}

private fun clampedCalendarDate(year: Int, month: Int, day: Int): LocalDate =
    LocalDate.of(year, month, day.coerceIn(1, java.time.YearMonth.of(year, month).lengthOfMonth()))

@Composable
private fun DateWheelDialog(initial: LocalDate, onDismiss: () -> Unit, onSave: (LocalDate) -> Unit) {
    var epoch by rememberSaveable { mutableLongStateOf(initial.toEpochDay()) }
    val date = LocalDate.ofEpochDay(epoch)
    val years = remember(initial) { ((initial.year - 100).coerceAtLeast(-999999999)..(initial.year + 100).coerceAtMost(999999999)).toList() }
    BricksDialog(onDismissRequest = onDismiss, title = { Text("选择日期") }, text = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WheelColumn(years, date.year, "年", Modifier.weight(1.4f), { it.toString() }) { epoch = clampedCalendarDate(it, date.monthValue, date.dayOfMonth).toEpochDay() }
            WheelColumn((1..12).toList(), date.monthValue, "月", Modifier.weight(1f)) { epoch = clampedCalendarDate(date.year, it, date.dayOfMonth).toEpochDay() }
            WheelColumn((1..date.lengthOfMonth()).toList(), date.dayOfMonth, "日", Modifier.weight(1f)) { epoch = date.withDayOfMonth(it).toEpochDay() }
        }
    }, confirmButton = { TextButton(onClick = { onSave(date) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun DateValueRow(label: String, day: Long, enabled: Boolean = true, onChange: (Long) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    ValueRow(label, LocalDate.ofEpochDay(day).toString(), enabled) { picking = true }
    if (picking) DateWheelDialog(LocalDate.ofEpochDay(day), { picking = false }) { onChange(it.toEpochDay()); picking = false }
}

@Composable
private fun TimeRangeRows(start: Int, end: Int, enabled: Boolean = true, onStart: (Int) -> Unit, onEnd: (Int) -> Unit,
    baseDay: Long = LocalDate.now().toEpochDay(), allowStartDate: Boolean = false) {
    var picking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf("start" to start, "end" to end).forEachIndexed { index, (target, value) ->
            if (index == 1) ChevronIcon()
            val date = LocalDate.ofEpochDay(baseDay + Math.floorDiv(value, 1440))
            Column(Modifier.weight(1f).clip(RoundedCornerShape(BricksDimensions.medium))
                .clickable(enabled = enabled) { picking = target }.padding(12.dp)) {
                Text(if (index == 0) "开始" else "结束", color = Secondary, fontSize = 13.sp)
                Text("%02d:%02d".format(Math.floorMod(value, 1440) / 60, Math.floorMod(value, 1440) % 60), color = Primary, fontSize = 26.sp)
                Text(if (date.year == LocalDate.ofEpochDay(baseDay).year) "${date.monthValue}月${date.dayOfMonth}日" else "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                    color = Secondary, fontSize = 16.sp)
            }
        }
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    picking?.let { target ->
        val value = if (target == "start") start else end
        TimeWheelDialog(value, LocalDate.ofEpochDay(baseDay + Math.floorDiv(value, 1440)),
            target == "end" || allowStartDate, { picking = null }) { date, minute ->
            val changed = (date.toEpochDay() - baseDay) * 1440L + minute
            val proposedStart = if (target == "start") changed else start.toLong()
            val proposedEnd = if (target == "end") changed else end.toLong()
            if (changed !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || proposedEnd <= proposedStart ||
                proposedEnd - Math.floorDiv(proposedStart, 1440L) * 1440L > MAX_EVENT_MINUTES) {
                error = "结束须晚于开始，最多占用366个日期"
            } else { if (target == "start") onStart(changed.toInt()) else onEnd(changed.toInt()); error = "" }
            picking = null
        }
    }
}

@Composable
private fun CategoryChoice(categories: List<Category>, categoryId: String, childId: String, enabled: Boolean = true,
    onChange: (String, String) -> Unit) {
    var choosing by remember { mutableStateOf<String?>(null) }
    val category = categories.firstOrNull { it.id == categoryId }
    val child = category?.children?.firstOrNull { it.id == childId }
    Box {
        ValueRow("分类", category?.name ?: "请选择", enabled) { choosing = "parent" }
        DropdownMenu(expanded = choosing == "parent", onDismissRequest = { choosing = null }) {
            categories.filter { !it.archived || it.id == categoryId }.forEach { c ->
                DropdownMenuItem(text = { Text(c.name + if (c.archived) "（归档）" else "") }, onClick = { onChange(c.id, ""); choosing = null })
            }
        }
    }
    if (category?.children?.isNotEmpty() == true) Box {
        ValueRow("子类", child?.name ?: "仅主分类", enabled) { choosing = "child" }
        DropdownMenu(expanded = choosing == "child", onDismissRequest = { choosing = null }) {
            DropdownMenuItem(text = { Text("仅主分类") }, onClick = { onChange(category.id, ""); choosing = null })
            category.children.filter { !it.archived || it.id == childId }.forEach { c ->
                DropdownMenuItem(text = { Text(c.name + if (c.archived) "（归档）" else "") }, onClick = { onChange(category.id, c.id); choosing = null })
            }
        }
    }
}

private fun weekdaySet(mask: Int): Set<Int> = (1..7).filter { mask and (1 shl (it - 1)) != 0 }.toSet()
private fun weekdayMask(days: Set<Int>): Int = days.fold(0) { mask, day -> mask or (1 shl (day - 1)) }

@Composable
private fun RepeatOptions(mode: String, onMode: (String) -> Unit, mask: Int, onMask: (Int) -> Unit,
    first: Long, last: Long?, onLast: (Long?) -> Unit,
    allowNone: Boolean, enabled: Boolean = true) {
    var pickingLast by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoice((if (allowNone) listOf("none" to "不重复") else emptyList()) + listOf("daily" to "每天", "weekly" to "每周"), mode, enabled, onMode)
        if (mode == "weekly") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (1..7).forEach { day ->
                val selected = day in weekdaySet(mask)
                Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(50))
                    .background(if (selected) Accent else TrackBg)
                    .selectable(selected, enabled = enabled, role = Role.Checkbox) { onMask(mask xor (1 shl (day - 1))) }
                    .semantics { contentDescription = "星期${listOf("一", "二", "三", "四", "五", "六", "日")[day - 1]}" },
                    contentAlignment = Alignment.Center) { Text(listOf("一", "二", "三", "四", "五", "六", "日")[day - 1], color = if (selected) Primary else Secondary, fontSize = 13.sp) }
            }
        }
        if (mode != "none") {
            ValueRow("重复截止", last?.let { LocalDate.ofEpochDay(it).toString() } ?: "不限", enabled) { pickingLast = true }
            if (last != null) TextButton(enabled = enabled, onClick = { onLast(null) }) { Text("设为不限") }
        }
    }
    if (pickingLast) DateWheelDialog(LocalDate.ofEpochDay(last ?: first), { pickingLast = false }) { onLast(it.toEpochDay()); pickingLast = false }
}



// Reminders deliberately consume the same effective daily projection as planning/statistics/export.
internal fun readReminderOccurrences(application: Application, first: Long, last: Long,
                                    zone: java.time.ZoneId): List<ReminderOccurrence> =
    LocalStore(application).load().reminderOccurrences(first, last, zone)

private fun AppData.reminderOccurrences(first: Long, last: Long, zone: java.time.ZoneId): List<ReminderOccurrence> {
    require(last >= first && last - first <= 10)
    return (first..last).flatMap { day ->
        eventsOn(day).filter { it.start in 0..1439 }.mapNotNull { event ->
            val category = categories.firstOrNull { it.id == event.categoryId } ?: return@mapNotNull null
            val child = category.children.firstOrNull { it.id == event.child }
            if (category.archived || child?.archived == true) return@mapNotNull null
            // Convert local wall times separately: DST days are not always 24 hours long.
            val midnight = LocalDate.ofEpochDay(day).atStartOfDay()
            val start = midnight.plusMinutes(event.start.toLong()).atZone(zone).toInstant().toEpochMilli()
            val end = midnight.plusMinutes(event.end.toLong()).atZone(zone).toInstant().toEpochMilli()
            if (end <= start) return@mapNotNull null
            val title = listOfNotNull(category.name, child?.name).joinToString(" · ")
            ReminderOccurrence("${event.originDay ?: day}:${event.id.length}:${event.id}", day, start, end, title, clockMinute(event.start))
        }
    }
}

@Composable
private fun ReminderSettings(store: TimeBricksStore, onStatusSchedule: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val status by ReminderSystem.status.collectAsState()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { ReminderSystem.refreshAsync(context) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) ReminderSystem.refreshAsync(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        ReminderSystem.refreshAsync(context)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun notificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && !asked) {
            asked = true
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            runCatching { context.startActivity(intent) }
        }
    }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("事件提醒", color = Primary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = status.enabled, enabled = store.loaded && !store.busy && store.loadError.isEmpty(), onCheckedChange = { enabled ->
                ReminderSystem.configure(context, enabled = enabled)
                if (enabled && !ReminderSystem.allowed(context)) notificationPermission()
            })
        }
        SettingsLink("默认提前量", reminderLeadLabel(status.minutes), enabled = !store.busy) { choosing = true }
        SettingsLink("系统通知", if (status.notificationsAllowed) "已允许" else "未允许 · 点击设置") { notificationPermission() }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            SettingsLink("准时提醒", if (status.exactAllowed) "已允许" else "普通提醒 · 可选授权") {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:${context.packageName}"))) }
            }
        }
    }
    SettingsGroup {
        SettingsLink("状态栏日程", if (StatusScheduleOverlay.isEnabled(context)) "已开启" else "已关闭",
            enabled = store.loaded && !store.busy, onClick = onStatusSchedule)
    }
    val summary = when {
        status.error.isNotEmpty() -> status.error
        !status.enabled -> "开启后按生效日程提醒"
        !status.notificationsAllowed -> "请允许系统通知后接收提醒"
        status.nextAt == null -> "未来7天暂无待提醒事件"
        else -> "下次提醒：" + Instant.ofEpochMilli(status.nextAt!!).atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }
    Text(summary, color = if (status.error.isEmpty()) Secondary else MaterialTheme.colorScheme.error, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 8.dp))
    if (status.error.isNotEmpty()) TextButton(onClick = { ReminderSystem.refreshAsync(context) }) { Text("重试") }
    if (status.enabled && !status.exactAllowed) Text("普通提醒可能因系统省电而延迟。需要更准时可授权“准时提醒”。",
        color = Secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
    if (choosing) CompactBricksDialog(onDismissRequest = { choosing = false }, title = { Text("默认提前量") },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text("取消") } }, text = {
            Column { listOf(0, 5, 10, 15, 30, 60).forEach { minutes ->
                Row(Modifier.fillMaxWidth().clickable {
                    ReminderSystem.configure(context, minutes = minutes); choosing = false
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(reminderLeadLabel(minutes), color = Primary, modifier = Modifier.weight(1f))
                    if (status.minutes == minutes) SelectedMark()
                }
            } }
        })
}


@Composable
private fun StatusScheduleSettings(store: TimeBricksStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(StatusScheduleOverlay.isEnabled(context)) }
    var allowed by remember { mutableStateOf(StatusScheduleOverlay.hasPermission(context)) }
    var pendingPermission by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(StatusScheduleOverlay.options(context)) }
    var pickingColor by remember { mutableStateOf(false) }
    val maxBottomDp = StatusScheduleOverlay.maxBottomDp(context)
    // Keep the editor preview capsule visible while this page is open, then remove it on exit.
    DisposableEffect(context, enabled) {
        if (enabled) StatusScheduleOverlay.setEditing(context, true)
        onDispose { if (enabled) StatusScheduleOverlay.setEditing(context, false) }
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        allowed = StatusScheduleOverlay.hasPermission(context)
        if (pendingPermission && allowed) {
            StatusScheduleOverlay.setEnabled(context, true)
            enabled = StatusScheduleOverlay.isEnabled(context)
        } else if (pendingPermission) {
            message = "没有获得悬浮窗权限，功能仍保持关闭"
        }
        pendingPermission = false
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                allowed = StatusScheduleOverlay.hasPermission(context)
                if (!allowed && StatusScheduleOverlay.isEnabled(context)) StatusScheduleOverlay.setEnabled(context, false)
                enabled = StatusScheduleOverlay.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun requestPermission(enableAfterGrant: Boolean) {
        pendingPermission = enableAfterGrant
        message = ""
        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"))
        try { permissionLauncher.launch(intent) }
        catch (_: Exception) {
            try {
                permissionLauncher.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                pendingPermission = false
                message = "无法打开系统悬浮窗授权页面，请在应用权限中手动允许"
            }
        }
    }
    fun changeOptions(updated: StatusScheduleOptions) {
        options = updated
        StatusScheduleOverlay.saveOptions(context, updated)
    }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("使用状态栏日程", color = Primary, fontSize = 15.sp)
                Text(if (enabled) "正在显示当前或下一项日程" else "关闭后不会显示悬浮文字",
                    color = Secondary, fontSize = 12.sp)
            }
            Switch(checked = enabled, enabled = store.loaded && !store.busy && store.loadError.isEmpty(),
                onCheckedChange = { active ->
                    message = ""
                    if (active && !StatusScheduleOverlay.hasPermission(context)) requestPermission(true)
                    else {
                        StatusScheduleOverlay.setEnabled(context, active)
                        enabled = StatusScheduleOverlay.isEnabled(context)
                    }
                })
        }
        SettingsLink("悬浮窗权限", if (allowed) "已允许" else "未允许 · 点击授权",
            enabled = !store.busy) { requestPermission(false) }
    }
    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 8.dp))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            @Composable
            fun settingSlider(title: String, number: Int, range: ClosedFloatingPointRange<Float>,
                update: (Int) -> StatusScheduleOptions) {
                Text("$title · $number", color = Primary, fontSize = 14.sp)
                Slider(value = number.toFloat().coerceIn(range.start, range.endInclusive), valueRange = range,
                    onValueChange = { changeOptions(update(it.roundToInt())) })
            }
            settingSlider("上下调节（距底部 dp）", options.bottomDp, 0f..maxBottomDp.toFloat().coerceAtLeast(1f)) {
                options.copy(bottomDp = it)
            }
            settingSlider("水平偏移（dp）", options.horizontalDp, -180f..180f) { options.copy(horizontalDp = it) }
            settingSlider("文字区域宽度（%）", options.widthPercent, 20f..100f) { options.copy(widthPercent = it) }
            settingSlider("字号（sp）", options.fontSp, 10f..24f) { options.copy(fontSp = it) }
            settingSlider("文字不透明度（%）", options.opacityPercent, 20f..100f) { options.copy(opacityPercent = it) }
            Text("文字颜色", color = Primary, fontSize = 14.sp)
            val presets = listOf(0xFFD0D0D0, 0xFFFFFFFF, 0xFFEC8B8B, 0xFF77C878, 0xFF64B7E7, 0xFFB09BDB)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                presets.forEach { color ->
                    Box(Modifier.size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(color))
                        .border(if (options.textColor == color.toInt()) 2.dp else 0.dp,
                            Primary, RoundedCornerShape(50))
                        .clickable { changeOptions(options.copy(textColor = color.toInt())) })
                }
                // Same custom picker already used by the app's theme/category colors.
                Box(Modifier.size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(
                        Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                        Color.Blue, Color.Magenta, Color.Red)))
                    .border(if (presets.none { it.toInt() == options.textColor }) 2.dp else 0.dp,
                        Primary, RoundedCornerShape(50))
                    .semantics { contentDescription = "自定义文字颜色" }
                    .clickable { pickingColor = true })
            }
        }
    }
    if (pickingColor) {
        ColorPickerDialog(Color(options.textColor), store.data.settings.recentColors,
            onDismiss = { pickingColor = false },
            onSave = { color ->
                changeOptions(options.copy(textColor = color.toArgb()))
                pickingColor = false
            })
    }
}

private fun reminderLeadLabel(minutes: Int) = if (minutes == 0) "开始时" else "提前${minutes}分钟"


// Widgets consume exactly the calendar projection, retaining historical archive intervals.
// Do not apply current category.archived a second time: eventsOn(day) owns that policy.
internal fun readWidgetDays(application: Application, days: Set<Long>): Map<Long, List<WidgetEvent>> {
    val data = LocalStore(application).load()
    return days.associateWith { day -> data.widgetEvents(day) }
}

internal fun readWidgetSnapshot(application: Application, days: Set<Long>): WidgetSnapshot {
    val data = LocalStore(application).load()
    return WidgetSnapshot(days.associateWith { day ->
        val now = java.time.LocalDateTime.now()
        val minute = if (day == now.toLocalDate().toEpochDay()) now.hour * 60 + now.minute else 720
        compactOvernightRows(data.widgetEvents(day), minute, { it.start }, { it.end }, { it.seriesKey })
    }, data.settings.themeMode)
}

private fun AppData.widgetEvents(day: Long): List<WidgetEvent> = eventsOn(day)
    .sortedWith(compareBy<DemoEvent> { it.start }.thenBy { it.end }.thenBy { it.id })
    .mapNotNull { event ->
        val category = categories.firstOrNull { it.id == event.categoryId } ?: return@mapNotNull null
        val child = category.children.firstOrNull { it.id == event.child }
        WidgetEvent(event.id, listOfNotNull(category.name, child?.name).joinToString(" · "),
            (child?.color ?: category.color).toArgb(), event.start, event.end, displaySeries(event, day))
    }

private fun AppData.displaySeries(event: DemoEvent, day: Long): String? {
    val identity = event.scheduleIdentity
        ?: ruleForOccurrence(event.originDay ?: day, event.id)?.id
        ?: return null
    return "$identity:${event.categoryId}:${event.child}"
}
