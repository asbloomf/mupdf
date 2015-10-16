package com.artifex.mupdfdemo;

import java.util.LinkedList;
import java.util.NoSuchElementException;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.ScaleAnimation;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.Scroller;

public class ReaderView
		extends AdapterView<Adapter>
		implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, ScaleGestureDetector.OnScaleGestureListener, Runnable {
	private static final int  MOVING_DIAGONALLY = 0;
	private static final int  MOVING_LEFT       = 1;
	private static final int  MOVING_RIGHT      = 2;
	private static final int  MOVING_UP         = 3;
	private static final int  MOVING_DOWN       = 4;

	private static final int  FLING_MARGIN      = 100;
	private static final int  GAP               = 20;

	private static final float MIN_SCALE        = 1.0f;
	private static final float MAX_SCALE        = 5.0f;
	private static final float REFLOW_SCALE_FACTOR = 0.5f;

	private static final boolean HORIZONTAL_SCROLLING = true;

	private Adapter           mAdapter;
	private int               mCurrent;    // Adapter's index for the current view
	private boolean           mResetLayout;
	private final SparseArray<View>
				  mChildViews = new SparseArray<View>(3);
					       // Shadows the children of the adapter view
					       // but with more sensible indexing
	private final LinkedList<View>
				  mViewCache = new LinkedList<View>();
	private boolean           mUserInteracting;  // Whether the user is interacting
	private boolean           mScaling;    // Whether the user is currently pinch zooming
	private float             mScale     = 1.0f;
	private int               mXScroll;    // Scroll amounts recorded from events.
	private int               mYScroll;    // and then accounted for in onLayout
	private boolean           mReflow = false;
	private boolean           mReflowChanged = false;
	private final GestureDetector
				  mGestureDetector;
	private final ScaleGestureDetector
				  mScaleGestureDetector;
	private final Scroller mScroller;
	private final Stepper     mStepper;
	private int               mScrollerLastX;
	private int               mScrollerLastY;
	private float		  mLastScaleFocusX;
	private float		  mLastScaleFocusY;

	static abstract class ViewMapper {
		abstract void applyToView(View view);
	}

	public ReaderView(Context context) {
		super(context);
		mGestureDetector = new GestureDetector(this);
		mScaleGestureDetector = new ScaleGestureDetector(context, this);
		mScroller        = new Scroller(context);
		mStepper = new Stepper(this, this);
	}

	public ReaderView(Context context, AttributeSet attrs) {
		super(context, attrs);
		
		// "Edit mode" means when the View is being displayed in the Android GUI editor. (this class
		// is instantiated in the IDE, so we need to be a bit careful what we do).
		if (isInEditMode())
		{
			mGestureDetector = null;
			mScaleGestureDetector = null;
			mScroller = null;
			mStepper = null;
		}
		else
		{
			mGestureDetector = new GestureDetector(this);
			mScaleGestureDetector = new ScaleGestureDetector(context, this);
			mScroller        = new Scroller(context);
			mStepper = new Stepper(this, this);
		}
	}

	public ReaderView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		mGestureDetector = new GestureDetector(this);
		mScaleGestureDetector = new ScaleGestureDetector(context, this);
		mScroller        = new Scroller(context);
		mStepper = new Stepper(this, this);
	}

	public int getDisplayedViewIndex() {
		return mCurrent;
	}

	public void setDisplayedViewIndex(int i) {
		if (0 <= i && i < mAdapter.getCount()) {
			onMoveOffChild(mCurrent);
			mCurrent = i;
			onMoveToChild(i);
			mResetLayout = true;
			requestLayout();
		}
	}

	public void moveToNext() {
		View v = mChildViews.get(mCurrent+1);
		if (v != null)
			slideViewOntoScreen(v);
	}

	public void moveToPrevious() {
		View v = mChildViews.get(mCurrent-1);
		if (v != null)
			slideViewOntoScreen(v);
	}

	// When advancing down the page, we want to advance by about
	// 90% of a screenful. But we'd be happy to advance by between
	// 80% and 95% if it means we hit the bottom in a whole number
	// of steps.
	private int smartAdvanceAmount(int screenHeight, int max) {
		int advance = (int)(screenHeight * 0.9 + 0.5);
		int leftOver = max % advance;
		int steps = max / advance;
		if (leftOver == 0) {
			// We'll make it exactly. No adjustment
		} else if ((float)leftOver / steps <= screenHeight * 0.05) {
			// We can adjust up by less than 5% to make it exact.
			advance += (int)((float)leftOver/steps + 0.5);
		} else {
			int overshoot = advance - leftOver;
			if ((float)overshoot / steps <= screenHeight * 0.1) {
				// We can adjust down by less than 10% to make it exact.
				advance -= (int)((float)overshoot/steps + 0.5);
			}
		}
		if (advance > max)
			advance = max;
		return advance;
	}

	public void smartMoveForwards(boolean isVertical) {
		View v = mChildViews.get(mCurrent);
		if (v == null)
			return;

		// The following code works in terms of where the screen is on the views;
		// so for example, if the currentView is at (-100,-100), the visible
		// region would be at (100,100). If the previous page was (2000, 3000) in
		// size, the visible region of the previous page might be (2100 + GAP, 100)
		// (i.e. off the previous page). This is different to the way the rest of
		// the code in this file is written, but it's easier for me to think about.
		// At some point we may refactor this to fit better with the rest of the
		// code.

		// screenWidth/Height are the actual width/height of the screen. e.g. 480/800
		int screenWidth  = getWidth();
		int screenHeight = getHeight();
		// We might be mid scroll; we want to calculate where we scroll to based on
		// where this scroll would end, not where we are now (to allow for people
		// bashing 'forwards' very fast.
		int remainingX = mScroller.getFinalX() - mScroller.getCurrX();
		int remainingY = mScroller.getFinalY() - mScroller.getCurrY();
		// right/bottom is in terms of pixels within the scaled document; e.g. 1000
		int top = -(v.getTop()  + mYScroll + remainingY);
		int left = -(v.getLeft() + mXScroll + remainingX);
		int right  = screenWidth +left;
		int bottom = screenHeight+top;
		// docWidth/Height are the width/height of the scaled document e.g. 2000x3000
		int docWidth  = v.getMeasuredWidth();
		int docHeight = v.getMeasuredHeight();

		int xOffset, yOffset;
		if(isVertical) {
			if (bottom >= docHeight) {
				// We are flush with the bottom. Advance to next column.
				if (right + (screenWidth/2) > docWidth) {
					// No room for another column - go to next page
					View nv = mChildViews.get(mCurrent + 1);
					if (nv == null) // No page to advance to
						return;
					int nextTop = -(nv.getTop() + mYScroll + remainingY);
					int nextLeft = -(nv.getLeft() + mXScroll + remainingX);
					int nextDocWidth = nv.getMeasuredWidth();
					int nextDocHeight = nv.getMeasuredHeight();

					// Allow for the next page maybe being shorter than the screen is high
					yOffset = (nextDocHeight < screenHeight ? ((nextDocHeight - screenHeight) >> 1) : 0);

					if (nextDocWidth < screenWidth) {
						// Next page is too narrow to fill the screen. Scroll to the top, centred.
						xOffset = (nextDocWidth - screenWidth) >> 1;
					} else {
						// Reset X back to the left hand column
						xOffset = right % screenWidth;
						if(xOffset > screenWidth/2)
								xOffset = 0;
						// Adjust in case the previous page is less wide
						if (xOffset + screenWidth > nextDocWidth)
							xOffset = nextDocWidth - screenWidth;
					}
					xOffset -= nextLeft;
					yOffset -= nextTop;
				} else {
					// Move to top of next column
					xOffset = screenWidth;
					if(right + xOffset > docWidth)
							xOffset = docWidth - right;
					yOffset = screenHeight - bottom;
				}
			} else {
				// Advance by 90% of the screen height downwards (in case lines are partially cut off)
				xOffset = 0;
				yOffset = smartAdvanceAmount(screenHeight, docHeight - bottom);
			}
		} else {
			// we want to go horizontal first, then down like a z
			if (right >= docWidth) {
				// We are flush with the right. Advance to next row.
				if (bottom + screenHeight > docHeight) {
					if(bottom < docHeight) {
						// No room for another full row - go to the very bottom
						xOffset = -left;
						yOffset = docHeight - bottom;
					} else {
						// go to next page
						View nv = mChildViews.get(mCurrent + 1);
						if (nv == null) // No page to advance to
							return;
						int nextTop = -(nv.getTop() + mYScroll + remainingY);
						int nextLeft = -(nv.getLeft() + mXScroll + remainingX);
						int nextDocWidth = nv.getMeasuredWidth();
						int nextDocHeight = nv.getMeasuredHeight();

						// Allow for the next page maybe being shorter than the screen is high
						yOffset = (nextDocHeight < screenHeight ? ((nextDocHeight - screenHeight) >> 1) : 0);

						if (nextDocWidth < screenWidth) {
							// Next page is too narrow to fill the screen. Scroll to the top, centred.
							xOffset = (nextDocWidth - screenWidth) >> 1;
						} else {
							xOffset = 0;
							// Reset X back to the left hand column
							/*xOffset = right % screenWidth;
							// Adjust in case the previous page is less wide
							if (xOffset + screenWidth > nextDocWidth)
								xOffset = nextDocWidth - screenWidth;
								*/
						}
						xOffset -= nextLeft;
						yOffset -= nextTop;
					}
				} else {
					// Move to left of next row
					xOffset = -left;
					yOffset = screenHeight;
				}
			} else {
				// Advance by up to 100% of the screen width rightwards
				xOffset = screenWidth;
				if(right + screenWidth > docWidth)
					xOffset += docWidth - (right + screenWidth);
				yOffset = 0;
			}
		}
		mScrollerLastX = mScrollerLastY = 0;
		mScroller.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 400);
		mStepper.prod();
	}

	public void smartMoveBackwards(boolean isVertical) {
		View v = mChildViews.get(mCurrent);
		if (v == null)
			return;

		// The following code works in terms of where the screen is on the views;
		// so for example, if the currentView is at (-100,-100), the visible
		// region would be at (100,100). If the previous page was (2000, 3000) in
		// size, the visible region of the previous page might be (2100 + GAP, 100)
		// (i.e. off the previous page). This is different to the way the rest of
		// the code in this file is written, but it's easier for me to think about.
		// At some point we may refactor this to fit better with the rest of the
		// code.

		// screenWidth/Height are the actual width/height of the screen. e.g. 480/800
		int screenWidth  = getWidth();
		int screenHeight = getHeight();
		// We might be mid scroll; we want to calculate where we scroll to based on
		// where this scroll would end, not where we are now (to allow for people
		// bashing 'forwards' very fast.
		int remainingX = mScroller.getFinalX() - mScroller.getCurrX();
		int remainingY = mScroller.getFinalY() - mScroller.getCurrY();
		// left/top is in terms of pixels within the scaled document; e.g. 1000
		int left  = -(v.getLeft() + mXScroll + remainingX);
		int top   = -(v.getTop()  + mYScroll + remainingY);
		int right  = screenWidth +left;
		// docWidth/Height are the width/height of the scaled document e.g. 2000x3000
		int docWidth  = v.getMeasuredWidth();
		int docHeight = v.getMeasuredHeight();

		int xOffset, yOffset;
		if(isVertical) {
			if (top <= 0) {
				// We are flush with the top. Step back to previous column.
				if (left < screenWidth/2) {
				/* No room for previous column - go to previous page */
					View pv = mChildViews.get(mCurrent - 1);
					if (pv == null) /* No page to advance to */
						return;
					int prevDocWidth = pv.getMeasuredWidth();
					int prevDocHeight = pv.getMeasuredHeight();

					// Allow for the next page maybe being shorter than the screen is high
					yOffset = (prevDocHeight < screenHeight ? ((prevDocHeight - screenHeight) >> 1) : 0);

					int prevLeft = -(pv.getLeft() + mXScroll);
					int prevTop = -(pv.getTop() + mYScroll);
					if (prevDocWidth < screenWidth) {
						// Previous page is too narrow to fill the screen. Scroll to the bottom, centred.
						xOffset = (prevDocWidth - screenWidth) >> 1;
					} else {
						// Reset X back to the right hand column
						xOffset = (left > 0 ? left % screenWidth : 0);
						if (xOffset + screenWidth > prevDocWidth)
							xOffset = prevDocWidth - screenWidth;
						while (xOffset + screenWidth * 2 < prevDocWidth)
							xOffset += screenWidth;
						if(xOffset < prevDocWidth - (3*screenWidth/2))
								xOffset = prevDocWidth - screenWidth;
					}
					xOffset -= prevLeft;
					yOffset -= prevTop - prevDocHeight + screenHeight;
				} else {
					// Move to bottom of previous column
					xOffset = -screenWidth;
					if(left + xOffset < 0)
							xOffset = -left;
					yOffset = docHeight - screenHeight + top;
				}
			} else {
				// Retreat by 90% of the screen height downwards (in case lines are partially cut off)
				xOffset = 0;
				yOffset = -smartAdvanceAmount(screenHeight, top);
			}
		} else {
			// we want to go horizontal first, then back like a z from the bottom
			if (left <= 0) {
				// We are flush with the left. Step back to previous row.
				if (top < screenHeight) {
					if(top > 0) {
						// No room for full row - go to the very top
						xOffset = docWidth-right;
						yOffset = -top;
					} else {
						// go to previous page
						View pv = mChildViews.get(mCurrent - 1);
						if (pv == null) // No page to advance to
							return;
						int prevDocWidth = pv.getMeasuredWidth();
						int prevDocHeight = pv.getMeasuredHeight();

						// Allow for the next page maybe being shorter than the screen is high
						yOffset = (prevDocHeight < screenHeight ? ((prevDocHeight - screenHeight) >> 1) : 0);

						int prevLeft = -(pv.getLeft() + mXScroll);
						int prevTop = -(pv.getTop() + mYScroll);
						if (prevDocWidth < screenWidth) {
							// Previous page is too narrow to fill the screen. Scroll to the bottom, centred.
							xOffset = (prevDocWidth - screenWidth) >> 1;
						} else {
							// Reset X back to the right hand column
							xOffset = prevDocWidth - screenWidth;
							/*xOffset = (left > 0 ? left % screenWidth : 0);
							if (xOffset + screenWidth > prevDocWidth)
								xOffset = prevDocWidth - screenWidth;
							while (xOffset + screenWidth * 2 < prevDocWidth)
								xOffset += screenWidth;*/
						}
						xOffset -= prevLeft;
						yOffset -= prevTop - prevDocHeight + screenHeight;
					}
				} else {
					// Move to right of previous row
					xOffset = docWidth-right;
					yOffset = -screenHeight;
				}
			} else {
				// Retreat by up to 100% of the screen width rightwards
				xOffset = -screenWidth;
				if(left + xOffset < 0)
					xOffset = -left;
				yOffset = 0;
			}
		}
		mScrollerLastX = mScrollerLastY = 0;
		mScroller.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 400);
		mStepper.prod();
	}

	public void resetupChildren() {
		for (int i = 0; i < mChildViews.size(); i++)
			onChildSetup(mChildViews.keyAt(i), mChildViews.valueAt(i));
	}

	public void applyToChildren(ViewMapper mapper) {
		for (int i = 0; i < mChildViews.size(); i++)
			mapper.applyToView(mChildViews.valueAt(i));
	}

	public void refresh(boolean reflow) {
		mReflow = reflow;
		mReflowChanged = true;
		mResetLayout = true;

		mScale = 1.0f;
		mXScroll = mYScroll = 0;

		requestLayout();
	}

	protected void onChildSetup(int i, View v) {}

	protected void onMoveToChild(int i) {}

	protected void onMoveOffChild(int i) {}

	protected void onSettle(View v) {};

	protected void onUnsettle(View v) {};

	protected void onNotInUse(View v) {};

	protected void onScaleChild(View v, Float scale) {};

	public View getView(int i) {
		return mChildViews.get(i);
	}

	public View getDisplayedView() {
		return mChildViews.get(mCurrent);
	}

	public void run() {
		if (!mScroller.isFinished()) {
			mScroller.computeScrollOffset();
			int x = mScroller.getCurrX();
			int y = mScroller.getCurrY();
			mXScroll += x - mScrollerLastX;
			mYScroll += y - mScrollerLastY;
			mScrollerLastX = x;
			mScrollerLastY = y;
			//float scale = mScroller.getCurrScale();
			//if(scale > 0) mScale = scale;
			requestLayout();
			mStepper.prod();
		}
		else if (!mUserInteracting) {
			// End of an inertial scroll and the user is not interacting.
			// The layout is stable
			View v = mChildViews.get(mCurrent);
			if (v != null)
				postSettle(v);
		}
	}

	public boolean onDown(MotionEvent arg0) {
		mScroller.forceFinished(true);
		return true;
	}

	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
			float velocityY) {
		if (mScaling)
			return true;

		View v = mChildViews.get(mCurrent);
		if (v != null) {
			Rect bounds = getScrollBounds(v);
			switch(directionOfTravel(velocityX, velocityY)) {
			case MOVING_LEFT:
				if (HORIZONTAL_SCROLLING && bounds.left >= 0) {
					// Fling off to the left bring next view onto screen
					View vl = mChildViews.get(mCurrent+1);

					if (vl != null) {
						slideViewOntoScreen(vl);
						return true;
					}
				}
				break;
			case MOVING_UP:
				if (!HORIZONTAL_SCROLLING && bounds.top >= 0) {
					// Fling off to the top bring next view onto screen
					View vl = mChildViews.get(mCurrent+1);

					if (vl != null) {
						slideViewOntoScreen(vl);
						return true;
					}
				}
				break;
			case MOVING_RIGHT:
				if (HORIZONTAL_SCROLLING && bounds.right <= 0) {
					// Fling off to the right bring previous view onto screen
					View vr = mChildViews.get(mCurrent-1);

					if (vr != null) {
						slideViewOntoScreen(vr);
						return true;
					}
				}
				break;
			case MOVING_DOWN:
				if (!HORIZONTAL_SCROLLING && bounds.bottom <= 0) {
					// Fling off to the bottom bring previous view onto screen
					View vr = mChildViews.get(mCurrent-1);

					if (vr != null) {
						slideViewOntoScreen(vr);
						return true;
					}
				}
				break;
			}
			mScrollerLastX = mScrollerLastY = 0;
			// If the page has been dragged out of bounds then we want to spring back
			// nicely. fling jumps back into bounds instantly, so we don't want to use
			// fling in that case. On the other hand, we don't want to forgo a fling
			// just because of a slightly off-angle drag taking us out of bounds other
			// than in the direction of the drag, so we test for out of bounds only
			// in the direction of travel.
			//
			// Also don't fling if out of bounds in any direction by more than fling
			// margin
			Rect expandedBounds = new Rect(bounds);
			expandedBounds.inset(-FLING_MARGIN, -FLING_MARGIN);

			if(withinBoundsInDirectionOfTravel(bounds, velocityX, velocityY)
					&& expandedBounds.contains(0, 0)) {
				mScroller.fling(0, 0, (int)velocityX, (int)velocityY, bounds.left, bounds.right, bounds.top, bounds.bottom);
				mStepper.prod();
			}
		}

		return true;
	}

	public void onLongPress(MotionEvent e) {
	}

	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
			float distanceY) {
		if (!mScaling) {
			mXScroll -= distanceX;
			mYScroll -= distanceY;
			requestLayout();
		}
		return true;
	}

	public void onShowPress(MotionEvent e) {
	}

	public boolean onSingleTapUp(MotionEvent e) {
		return false;
	}

	public boolean onDoubleTap(MotionEvent e) {
		if(!mReflow) {
			View v = mChildViews.get(mCurrent);
			if (v != null) {
				float scale = (float)getWidth() / v.getMeasuredWidth();
				float newScale = (mScale > 1)? 1: (scale < 1.5? 2: scale);
				float factor = newScale/mScale;

				int xScroll, yScroll;

				if(newScale >= 1.5) {
					//put a 25% margin around it, so that clicking 25% inside the edge is like clicking all the way at the edge
					float currentFocusX = 2 * e.getX() - getWidth() / 2.f;
					if (currentFocusX < 0) currentFocusX = 0;
					else if (currentFocusX > getWidth()) currentFocusX = getWidth();
					float currentFocusY = 2 * e.getY() - getHeight() / 2.f;
					if (currentFocusY < 0) currentFocusY = 0;
					else if (currentFocusY > getHeight())
						currentFocusY = getHeight();
					// Work out the focus point relative to the view top left
					int viewFocusX = (int) currentFocusX - (v.getLeft() + mXScroll);
					int viewFocusY = (int) currentFocusY - (v.getTop() + mYScroll);
					// Scroll to maintain the focus point
					xScroll = mXScroll + viewFocusX - (int)(viewFocusX * factor);
					yScroll = mYScroll + viewFocusY - (int)(viewFocusY * factor);

					/*if (mLastScaleFocusX>=0)
						mXScroll+=currentFocusX-mLastScaleFocusX;
					if (mLastScaleFocusY>=0)
						mYScroll+=currentFocusY-mLastScaleFocusY;

					mLastScaleFocusX=currentFocusX;
					mLastScaleFocusY=currentFocusY;*/
				} else {
					xScroll = mXScroll;
					yScroll = mYScroll;
				}
				mXScroll = mYScroll = 0;
				if(v.getWidth()*factor <= getWidth()) {
					// if the width of the document doesn't cover the whole screen, center it
					xScroll = -v.getLeft() + (int) ((getWidth() - v.getWidth() * factor) / 2);
				} else if(v.getWidth() *factor <= getWidth()) {
					// if the width of the document does cover the whole screen, ensure it doesn't go off the edge
					if(v.getLeft() + xScroll > 0) xScroll = -v.getLeft();
					else if(v.getRight() + xScroll < v.getWidth()) xScroll = v.getWidth() - v.getRight();
				}
				if(v.getHeight()*factor <= getHeight()) {
					yScroll = -v.getTop() + (int) ((getHeight() - v.getHeight() * factor) / 2);
				} else {
					if(v.getTop() + yScroll > 0) yScroll = -v.getTop();
					else if(v.getTop() + v.getHeight()*factor + yScroll < getHeight()) yScroll = getHeight() - (int)(v.getTop() + v.getHeight()*factor);
				}
				//mScroller.startZoom(0, 0, xScroll, yScroll, mScale, newScale);
				//mStepper.prod();
				mXScroll = xScroll;
				mYScroll = yScroll;
				mScale = newScale;
				requestLayout();
			}
		}
		return false;
	}

	public boolean onDoubleTapEvent(MotionEvent e) {
		return false;
	}

	public boolean onSingleTapConfirmed(MotionEvent e) {
		return false;
	}

	public boolean onScale(ScaleGestureDetector detector) {
		float previousScale = mScale;
		float scale_factor = mReflow ? REFLOW_SCALE_FACTOR : 1.0f;
		float min_scale = MIN_SCALE * scale_factor;
		float max_scale = MAX_SCALE * scale_factor;
		mScale = Math.min(Math.max(mScale * detector.getScaleFactor(), min_scale), max_scale);

		if (mReflow) {
			View v = mChildViews.get(mCurrent);
			if (v != null)
				onScaleChild(v, mScale);
		} else {
			float factor = mScale/previousScale;

			View v = mChildViews.get(mCurrent);
			if (v != null) {
				float currentFocusX = detector.getFocusX();
				float currentFocusY = detector.getFocusY();
				// Work out the focus point relative to the view top left
				int viewFocusX = (int)currentFocusX - (v.getLeft() + mXScroll);
				int viewFocusY = (int)currentFocusY - (v.getTop() + mYScroll);
				// Scroll to maintain the focus point
				mXScroll += viewFocusX - viewFocusX * factor;
				mYScroll += viewFocusY - viewFocusY * factor;

				if (mLastScaleFocusX>=0)
					mXScroll+=currentFocusX-mLastScaleFocusX;
				if (mLastScaleFocusY>=0)
					mYScroll+=currentFocusY-mLastScaleFocusY;

				mLastScaleFocusX=currentFocusX;
				mLastScaleFocusY=currentFocusY;
				requestLayout();
			}
		}
		return true;
	}

	public boolean onScaleBegin(ScaleGestureDetector detector) {
		mScaling = true;
		// Ignore any scroll amounts yet to be accounted for: the
		// screen is not showing the effect of them, so they can
		// only confuse the user
		mXScroll = mYScroll = 0;
		mLastScaleFocusX = mLastScaleFocusY = -1;
		return true;
	}

	public void onScaleEnd(ScaleGestureDetector detector) {
		if (mReflow) {
			applyToChildren(new ViewMapper() {
				@Override
				void applyToView(View view) {
					onScaleChild(view, mScale);
				}
			});
		}
		mScaling = false;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		mScaleGestureDetector.onTouchEvent(event);
		mGestureDetector.onTouchEvent(event);

		if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
			mUserInteracting = true;
		}
		if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
			mUserInteracting = false;

			View v = mChildViews.get(mCurrent);
			if (v != null) {
				if (mScroller.isFinished()) {
					// If, at the end of user interaction, there is no
					// current inertial scroll in operation then animate
					// the view onto screen if necessary
					slideViewOntoScreen(v);
				}

				if (mScroller.isFinished()) {
					// If still there is no inertial scroll in operation
					// then the layout is stable
					postSettle(v);
				}
			}
		}

		return true;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);

		int n = getChildCount();
		for (int i = 0; i < n; i++)
			measureView(getChildAt(i));
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right,
			int bottom) {
		super.onLayout(changed, left, top, right, bottom);

		// "Edit mode" means when the View is being displayed in the Android GUI editor. (this class
		// is instantiated in the IDE, so we need to be a bit careful what we do).
		if (isInEditMode())
			return;

		View cv = mChildViews.get(mCurrent);
		Point cvOffset;

		if (!mResetLayout) {
			// Move to next or previous if current is sufficiently off center
			if (cv != null) {
				boolean move;
				cvOffset = subScreenSizeOffset(cv);
				// cv.getRight() may be out of date with the current scale
				// so add left to the measured width for the correct position
				if (HORIZONTAL_SCROLLING)
					move = cv.getLeft() + cv.getMeasuredWidth() + cvOffset.x + GAP/2 + mXScroll < getWidth()/2;
				else
					move = cv.getTop() + cv.getMeasuredHeight() + cvOffset.y + GAP/2 + mYScroll < getHeight()/2;
				if (move && mCurrent + 1 < mAdapter.getCount()) {
					postUnsettle(cv);
					// post to invoke test for end of animation
					// where we must set hq area for the new current view
					mStepper.prod();

					onMoveOffChild(mCurrent);
					mCurrent++;
					onMoveToChild(mCurrent);
				}

				if (HORIZONTAL_SCROLLING)
					move = cv.getLeft() - cvOffset.x - GAP/2 + mXScroll >= getWidth()/2;
				else
					move = cv.getTop() - cvOffset.y - GAP/2 + mYScroll >= getHeight()/2;
				if (move && mCurrent > 0) {
					postUnsettle(cv);
					// post to invoke test for end of animation
					// where we must set hq area for the new current view
					mStepper.prod();

					onMoveOffChild(mCurrent);
					mCurrent--;
					onMoveToChild(mCurrent);
				}
			}

			// Remove not needed children and hold them for reuse
			int numChildren = mChildViews.size();
			int childIndices[] = new int[numChildren];
			for (int i = 0; i < numChildren; i++)
				childIndices[i] = mChildViews.keyAt(i);

			for (int i = 0; i < numChildren; i++) {
				int ai = childIndices[i];
				if (ai < mCurrent - 1 || ai > mCurrent + 1) {
					View v = mChildViews.get(ai);
					onNotInUse(v);
					mViewCache.add(v);
					removeViewInLayout(v);
					mChildViews.remove(ai);
				}
			}
		} else {
			mResetLayout = false;
			mXScroll = mYScroll = 0;

			// Remove all children and hold them for reuse
			int numChildren = mChildViews.size();
			for (int i = 0; i < numChildren; i++) {
				View v = mChildViews.valueAt(i);
				onNotInUse(v);
				mViewCache.add(v);
				removeViewInLayout(v);
			}
			mChildViews.clear();

			// Don't reuse cached views if the adapter has changed
			if (mReflowChanged) {
				mReflowChanged = false;
				mViewCache.clear();
			}

			// post to ensure generation of hq area
			mStepper.prod();
		}

		// Ensure current view is present
		int cvLeft, cvRight, cvTop, cvBottom;
		boolean notPresent = (mChildViews.get(mCurrent) == null);
		cv = getOrCreateChild(mCurrent);
		// When the view is sub-screen-size in either dimension we
		// offset it to center within the screen area, and to keep
		// the views spaced out
		cvOffset = subScreenSizeOffset(cv);
		if (notPresent) {
			//Main item not already present. Just place it top left
			cvLeft = cvOffset.x;
			cvTop  = cvOffset.y;
		} else {
			// Main item already present. Adjust by scroll offsets
			cvLeft = cv.getLeft() + mXScroll;
			cvTop  = cv.getTop()  + mYScroll;
		}
		// Scroll values have been accounted for
		mXScroll = mYScroll = 0;
		cvRight  = cvLeft + cv.getMeasuredWidth();
		cvBottom = cvTop  + cv.getMeasuredHeight();

		if (!mUserInteracting && mScroller.isFinished()) {
			Point corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom));
			cvRight  += corr.x;
			cvLeft   += corr.x;
			cvTop    += corr.y;
			cvBottom += corr.y;
		} else if (HORIZONTAL_SCROLLING && cv.getMeasuredHeight() <= getHeight()) {
			// When the current view is as small as the screen in height, clamp
			// it vertically
			Point corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom));
			cvTop    += corr.y;
			cvBottom += corr.y;
		} else if (!HORIZONTAL_SCROLLING && cv.getMeasuredWidth() <= getWidth()) {
			// When the current view is as small as the screen in width, clamp
			// it horizontally
			Point corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom));
			cvRight  += corr.x;
			cvLeft   += corr.x;
		}

		cv.layout(cvLeft, cvTop, cvRight, cvBottom);

		if (mCurrent > 0) {
			View lv = getOrCreateChild(mCurrent - 1);
			Point leftOffset = subScreenSizeOffset(lv);
			if (HORIZONTAL_SCROLLING)
			{
				int gap = leftOffset.x + GAP + cvOffset.x;
				lv.layout(cvLeft - lv.getMeasuredWidth() - gap,
						(cvBottom + cvTop - lv.getMeasuredHeight())/2,
						cvLeft - gap,
						(cvBottom + cvTop + lv.getMeasuredHeight())/2);
			} else {
				int gap = leftOffset.y + GAP + cvOffset.y;
				lv.layout((cvLeft + cvRight - lv.getMeasuredWidth())/2,
						cvTop - lv.getMeasuredHeight() - gap,
						(cvLeft + cvRight + lv.getMeasuredWidth())/2,
						cvTop - gap);
			}
		}

		if (mCurrent + 1 < mAdapter.getCount()) {
			View rv = getOrCreateChild(mCurrent + 1);
			Point rightOffset = subScreenSizeOffset(rv);
			if (HORIZONTAL_SCROLLING)
			{
				int gap = cvOffset.x + GAP + rightOffset.x;
				rv.layout(cvRight + gap,
						(cvBottom + cvTop - rv.getMeasuredHeight())/2,
						cvRight + rv.getMeasuredWidth() + gap,
						(cvBottom + cvTop + rv.getMeasuredHeight())/2);
			} else {
				int gap = cvOffset.y + GAP + rightOffset.y;
				rv.layout((cvLeft + cvRight - rv.getMeasuredWidth())/2,
						cvBottom + gap,
						(cvLeft + cvRight + rv.getMeasuredWidth())/2,
						cvBottom + gap + rv.getMeasuredHeight());
			}
		}

		invalidate();
	}

	@Override
	public Adapter getAdapter() {
		return mAdapter;
	}

	@Override
	public View getSelectedView() {
		return null;
	}

	@Override
	public void setAdapter(Adapter adapter) {
		mAdapter = adapter;

		requestLayout();
	}

	@Override
	public void setSelection(int arg0) {
		throw new UnsupportedOperationException(getContext().getString(R.string.not_supported));
	}

	private View getCached() {
		if (mViewCache.size() == 0)
			return null;
		else
			return mViewCache.removeFirst();
	}

	private View getOrCreateChild(int i) {
		View v = mChildViews.get(i);
		if (v == null) {
			v = mAdapter.getView(i, getCached(), this);
			addAndMeasureChild(i, v);
			onChildSetup(i, v);
			onScaleChild(v, mScale);
		}

		return v;
	}

	private void addAndMeasureChild(int i, View v) {
		LayoutParams params = v.getLayoutParams();
		if (params == null) {
			params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
		}
		addViewInLayout(v, 0, params, true);
		mChildViews.append(i, v); // Record the view against it's adapter index
		measureView(v);
	}

	private void measureView(View v) {
		// See what size the view wants to be
		v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

		if (!mReflow) {
		// Work out a scale that will fit it to this view
		float scale = Math.min((float)getWidth()/(float)v.getMeasuredWidth(),
					(float)getHeight()/(float)v.getMeasuredHeight());
		// Use the fitting values scaled by our current scale factor
		v.measure(View.MeasureSpec.EXACTLY | (int)(v.getMeasuredWidth()*scale*mScale),
				View.MeasureSpec.EXACTLY | (int)(v.getMeasuredHeight()*scale*mScale));
		} else {
			v.measure(View.MeasureSpec.EXACTLY | (int)(v.getMeasuredWidth()),
					View.MeasureSpec.EXACTLY | (int)(v.getMeasuredHeight()));
		}
	}

	private Rect getScrollBounds(int left, int top, int right, int bottom) {
		int xmin = getWidth() - right;
		int xmax = -left;
		int ymin = getHeight() - bottom;
		int ymax = -top;

		// In either dimension, if view smaller than screen then
		// constrain it to be central
		if (xmin > xmax) xmin = xmax = (xmin + xmax)/2;
		if (ymin > ymax) ymin = ymax = (ymin + ymax)/2;

		return new Rect(xmin, ymin, xmax, ymax);
	}

	private Rect getScrollBounds(View v) {
		// There can be scroll amounts not yet accounted for in
		// onLayout, so add mXScroll and mYScroll to the current
		// positions when calculating the bounds.
		return getScrollBounds(v.getLeft() + mXScroll,
				               v.getTop() + mYScroll,
				               v.getLeft() + v.getMeasuredWidth() + mXScroll,
				               v.getTop() + v.getMeasuredHeight() + mYScroll);
	}

	private Point getCorrection(Rect bounds) {
		return new Point(Math.min(Math.max(0,bounds.left),bounds.right),
				         Math.min(Math.max(0,bounds.top),bounds.bottom));
	}

	private void postSettle(final View v) {
		// onSettle and onUnsettle are posted so that the calls
		// wont be executed until after the system has performed
		// layout.
		post (new Runnable() {
			public void run () {
				onSettle(v);
			}
		});
	}

	private void postUnsettle(final View v) {
		post (new Runnable() {
			public void run () {
				onUnsettle(v);
			}
		});
	}

	private void slideViewOntoScreen(View v) {
		Point corr = getCorrection(getScrollBounds(v));
		if (corr.x != 0 || corr.y != 0) {
			mScrollerLastX = mScrollerLastY = 0;
			mScroller.startScroll(0, 0, corr.x, corr.y, 400);
			mStepper.prod();
		}
	}

	private Point subScreenSizeOffset(View v) {
		return new Point(Math.max((getWidth() - v.getMeasuredWidth())/2, 0),
				Math.max((getHeight() - v.getMeasuredHeight())/2, 0));
	}

	private static int directionOfTravel(float vx, float vy) {
		if (Math.abs(vx) > 2 * Math.abs(vy))
			return (vx > 0) ? MOVING_RIGHT : MOVING_LEFT;
		else if (Math.abs(vy) > 2 * Math.abs(vx))
			return (vy > 0) ? MOVING_DOWN : MOVING_UP;
		else
			return MOVING_DIAGONALLY;
	}

	private static boolean withinBoundsInDirectionOfTravel(Rect bounds, float vx, float vy) {
		switch (directionOfTravel(vx, vy)) {
		case MOVING_DIAGONALLY: return bounds.contains(0, 0);
		case MOVING_LEFT:       return bounds.left <= 0;
		case MOVING_RIGHT:      return bounds.right >= 0;
		case MOVING_UP:         return bounds.top <= 0;
		case MOVING_DOWN:       return bounds.bottom >= 0;
		default: throw new NoSuchElementException();
		}
	}
}
