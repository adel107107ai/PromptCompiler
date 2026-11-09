package com.promptengine.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.EditText;

/**
 * A multi-line input that scrolls its own text when it runs out of room.
 *
 * The input sits inside a ScrollView, and a ScrollView claims every vertical drag. That is fine
 * while the box grows with its content, but once the box is capped at its max height the text
 * beyond the visible area cannot be reached at all: the box would scroll, and the page takes the
 * gesture instead.
 *
 * So while the box still has something to scroll it keeps the drag for itself, and it hands the
 * gesture back as soon as the finger lifts, which leaves the page scrollable from anywhere outside
 * the box.
 *
 * The height cap is applied here as well as through android:maxHeight, so the scrolling behaviour
 * does not depend on how the framework resolves that attribute inside a ScrollView.
 */
public class ScrollableEditText extends EditText {

    public ScrollableEditText(Context context) {
        super(context);
    }

    public ScrollableEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ScrollableEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int maximum = getMaxHeight();
        if (maximum > 0 && getMeasuredHeight() > maximum) {
            setMeasuredDimension(getMeasuredWidth(), maximum);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        ViewParent parent = getParent();
        if (parent != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    parent.requestDisallowInterceptTouchEvent(hasRoomToScroll());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    parent.requestDisallowInterceptTouchEvent(false);
                    break;
                default:
                    break;
            }
        }
        return super.onTouchEvent(event);
    }

    private boolean hasRoomToScroll() {
        try {
            return canScrollVertically(-1) || canScrollVertically(1);
        } catch (Throwable ignored) {
            // Never let a measurement edge case turn a tap into a crash.
            return false;
        }
    }
}
