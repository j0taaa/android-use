package dev.androiduse.app

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.ScrollView

/** A real, harmless UI for checking accessibility. No simulated tool results. */
class PracticeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = column().apply { setPadding(dp(24), dp(48), dp(24), dp(30)); setBackgroundColor(Palette.bg) }
        body.fill(label("ANDROID USE / PRACTICE", 11f, Palette.accent, true)); body.gap(18)
        body.fill(label("A safe place to try.", 30f, Palette.ink, true)); body.gap(12)
        body.fill(label("Ask the agent to write and save a note here. Nothing is sent or published.", 15f, Palette.muted)); body.gap(24)
        val input = EditText(this).apply { hint = "Your practice note"; contentDescription = "Practice note"; setTextColor(Palette.ink); setHintTextColor(Palette.muted); minLines = 2 }
        body.fill(input); body.gap(12)
        val result = label("No note saved yet", 16f, Palette.muted).apply { contentDescription = "Save result" }
        body.fill(action("Save note", true) { result.text = "Saved: ${input.text}" }); body.gap(16); body.fill(result); body.gap(18)
        body.fill(action("Long press me") {}.apply { setOnLongClickListener { result.text = "Long press worked"; true } }); body.gap(24)
        for (i in 1..18) { body.fill(label("Practice row $i", 16f)); body.gap(22) }
        body.fill(label("You reached the end", 20f, Palette.accent, true)); body.gap(12)
        body.fill(action("Back to Android Use") { finish() })
        val scroll = ScrollView(this).apply { addView(body) }
        scroll.setOnApplyWindowInsetsListener { v, insets -> val b = insets.getInsets(android.view.WindowInsets.Type.systemBars()); v.setPadding(b.left,b.top,b.right,b.bottom); insets }
        setContentView(scroll)
    }
}
