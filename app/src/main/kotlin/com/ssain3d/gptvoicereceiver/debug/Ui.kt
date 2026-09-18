package com.ssain3d.gptvoicereceiver.debug

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimal view helpers.
 *
 * Why framework Views and not Compose, and why no androidx import in this
 * module's own UI code: this APK's job is to be built and side-loaded quickly on
 * a Galaxy to answer three questions. Every extra version-coupled dependency is
 * one more way for the build to fail before a single measurement is taken. Zero
 * third-party UI dependencies means the only dependencies this module declares
 * are AGP, Kotlin and Porcupine. The brief allows Compose; it does not require
 * it, and the dashboard has no need for it.
 *
 * That is a statement about the code here, not about what the build resolves.
 * Porcupine pulls androidx.core in transitively, so the APK does ship AndroidX
 * and gradle.properties has to set android.useAndroidX=true. Nothing in this
 * file uses it.
 */

fun Context.dp(value: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
).toInt()

fun Context.column(padding: Int = 16, block: LinearLayout.() -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        block()
    }

fun Context.scroller(child: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(child, MATCH_PARENT, WRAP_CONTENT)
}

fun LinearLayout.heading(text: String): TextView = TextView(context).apply {
    this.text = text
    setTypeface(Typeface.DEFAULT_BOLD)
    textSize = 18f
    setPadding(0, context.dp(16), 0, context.dp(6))
    this@heading.addView(this, MATCH_PARENT, WRAP_CONTENT)
}

fun LinearLayout.body(text: String, mono: Boolean = false): TextView = TextView(context).apply {
    this.text = text
    textSize = if (mono) 12f else 14f
    if (mono) typeface = Typeface.MONOSPACE
    setTextIsSelectable(true)
    setPadding(0, context.dp(2), 0, context.dp(2))
    this@body.addView(this, MATCH_PARENT, WRAP_CONTENT)
}

fun LinearLayout.note(text: String): TextView = body(text).apply {
    textSize = 12f
    alpha = 0.75f
}

fun LinearLayout.button(text: String, onClick: () -> Unit): Button = Button(context).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
    this@button.addView(this, MATCH_PARENT, WRAP_CONTENT)
}

fun LinearLayout.input(hint: String, initial: String = "", singleLine: Boolean = true): EditText =
    EditText(context).apply {
        this.hint = hint
        setText(initial)
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = singleLine
        this@input.addView(this, MATCH_PARENT, WRAP_CONTENT)
    }

fun LinearLayout.divider(): View = View(context).apply {
    setBackgroundColor(Color.argb(60, 128, 128, 128))
    this@divider.addView(this, MATCH_PARENT, context.dp(1))
}

/** Compact status line: "Microphone   ✓". */
fun LinearLayout.statusRow(label: String, state: Tri, detail: String? = null): TextView {
    val mark = when (state) {
        Tri.YES -> "✓"
        Tri.NO -> "✗"
        Tri.UNKNOWN -> "?"
    }
    return body(buildString {
        append(label.padEnd(24))
        append(mark)
        if (detail != null) append("  ").append(detail)
    }, mono = true).apply {
        setTextColor(
            when (state) {
                Tri.YES -> Color.rgb(0x2E, 0x7D, 0x32)
                Tri.NO -> Color.rgb(0xC6, 0x28, 0x28)
                Tri.UNKNOWN -> Color.rgb(0xF5, 0x7C, 0x00)
            }
        )
        gravity = Gravity.START
    }
}

enum class Tri { YES, NO, UNKNOWN }

fun Boolean.tri(): Tri = if (this) Tri.YES else Tri.NO
