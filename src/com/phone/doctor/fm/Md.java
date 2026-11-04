package com.phone.doctor.fm;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/** Tiny markdown renderer: headings, bullets, **bold**, `code`. */
final class Md {
    /** The answer as plain text for the clipboard: no ** / ` / # markers. */
    static String plain(String s) {
        StringBuilder b = new StringBuilder();
        for (String l : s.split("\n", -1)) {
            String x = l;
            if (x.trim().startsWith("```")) continue;
            x = x.replaceFirst("^(\\s*)#{1,6}\\s+", "$1").replaceFirst("^(\\s*)[*\\-]\\s+", "$1• ");
            x = x.replace("**", "").replace("`", "");
            b.append(x).append('\n');
        }
        return b.toString().trim();
    }

    static CharSequence render(String s, int codeBg) {
        SpannableStringBuilder o = new SpannableStringBuilder();
        for (String line : s.split("\n", -1)) {
            String l = line;
            if (l.trim().startsWith("```")) continue;
            boolean head = false;
            if (l.matches("^\\s*#{1,6}\\s+.*")) {
                l = l.replaceFirst("^\\s*#{1,6}\\s+", "");
                head = true;
            } else if (l.matches("^\\s*[*-]\\s+.*")) {
                l = "\u2022 " + l.replaceFirst("^\\s*[*-]\\s+", "");
            }
            int st = o.length();
            inline(o, l, codeBg);
            if (head) {
                o.setSpan(new StyleSpan(Typeface.BOLD), st, o.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                o.setSpan(new RelativeSizeSpan(1.15f), st, o.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            o.append('\n');
        }
        while (o.length() > 0 && o.charAt(o.length() - 1) == '\n') o.delete(o.length() - 1, o.length());
        return o;
    }

    private static void inline(SpannableStringBuilder o, String l, int codeBg) {
        int i = 0;
        while (i < l.length()) {
            if (l.startsWith("**", i)) {
                int e = l.indexOf("**", i + 2);
                if (e > 0) {
                    int st = o.length();
                    o.append(l, i + 2, e);
                    o.setSpan(new StyleSpan(Typeface.BOLD), st, o.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = e + 2;
                    continue;
                }
            }
            if (l.charAt(i) == '`') {
                int e = l.indexOf('`', i + 1);
                if (e > 0) {
                    int st = o.length();
                    o.append(l, i + 1, e);
                    o.setSpan(new TypefaceSpan("monospace"), st, o.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    o.setSpan(new BackgroundColorSpan(codeBg), st, o.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = e + 1;
                    continue;
                }
            }
            o.append(l.charAt(i));
            i++;
        }
    }
}
