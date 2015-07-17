/**
 * Copyright 2012 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */

package jogamp.newt.driver.bcm.vc.iv;

import com.jogamp.nativewindow.AbstractGraphicsConfiguration;
import com.jogamp.nativewindow.AbstractGraphicsScreen;
import com.jogamp.nativewindow.Capabilities;
import com.jogamp.nativewindow.DefaultGraphicsScreen;
import com.jogamp.nativewindow.GraphicsConfigurationFactory;
import com.jogamp.nativewindow.NativeWindowException;
import com.jogamp.nativewindow.VisualIDHolder;
import com.jogamp.nativewindow.util.Insets;
import com.jogamp.nativewindow.util.Point;
import com.jogamp.nativewindow.util.Rectangle;
import com.jogamp.nativewindow.util.RectangleImmutable;
import com.jogamp.common.util.IntBitfield;
import com.jogamp.nativewindow.egl.EGLGraphicsDevice;
import com.jogamp.newt.event.MouseEvent;

import jogamp.newt.PointerIconImpl;
import jogamp.newt.WindowImpl;
import jogamp.newt.driver.linux.LinuxEventDeviceTracker;
import jogamp.newt.driver.linux.LinuxMouseTracker;
import jogamp.opengl.egl.EGLDisplayUtil;

public class WindowDriver extends WindowImpl {
    private static final String WINDOW_CLASS_NAME = "NewtWindow";

    static {
        DisplayDriver.initSingleton();
    }

    public WindowDriver() {
        linuxMouseTracker = LinuxMouseTracker.getSingleton();
        linuxEventDeviceTracker = LinuxEventDeviceTracker.getSingleton();
        layer = -1;
        nativeWindowHandle = 0;
        windowHandleClose = 0;
    }

    /**
     * Clamp given rectangle to given screen bounds.
     *
     * @param screen
     * @param rect the {@link RectangleImmutable} in pixel units
     * @return If position or size has been clamped a new {@link RectangleImmutable} instance w/ clamped values
     *         will be returned, otherwise the given {@code rect} is returned.
     */
    private RectangleImmutable clampRect(final ScreenDriver screen, final RectangleImmutable rect) {
        int x = rect.getX();
        int y = rect.getY();
        int w = rect.getWidth();
        int h = rect.getHeight();
        final int s_w = screen.getWidth();
        final int s_h = screen.getHeight();
        boolean mod = false;
        if( 0 > x ) {
            x = 0;
            mod = true;
        }
        if( 0 > y ) {
            y = 0;
            mod = true;
        }
        if( s_w < x + w ) {
            if( 0 < x ) {
                x = 0;
                mod = true;
            }
            if( s_w < w ) {
                w = s_w;
                mod = true;
            }
        }
        if( s_h < y + h ) {
            if( 0 < y ) {
                y = 0;
                mod = true;
            }
            if( s_h < h ) {
                h = s_h;
                mod = true;
            }
        }
        if( mod ) {
            return new Rectangle(x, y, w, h);
        } else {
            return rect;
        }
    }

    @Override
    protected void createNativeImpl() {
        if(0!=getParentWindowHandle()) {
            throw new RuntimeException("Window parenting not supported (yet)");
        }
        synchronized( layerSync ) {
            if( layerCount >= MAX_LAYERS ) {
                throw new RuntimeException("Max windows reached: "+layerCount+" ( "+MAX_LAYERS+" )");
            }
            for(int i=0; 0 > layer && i<MAX_LAYERS; i++) {
                if( !usedLayers.get(nextLayer) ) {
                    layer = nextLayer;
                    usedLayers.put(layer, true);
                    layerCount++;
                }
                nextLayer++;
                if( MAX_LAYERS == nextLayer ) {
                    nextLayer=0;
                }
            }
            // System.err.println("XXX.Open capacity "+usedLayers.capacity()+", count "+usedLayers.getBitCount());
        }
        if( 0 > layer ) {
            throw new InternalError("Could not find a free layer: count "+layerCount+", max "+MAX_LAYERS);
        }
        final ScreenDriver screen = (ScreenDriver) getScreen();
        final DisplayDriver display = (DisplayDriver) screen.getDisplay();

        // Create own screen/device resource instance allowing independent ownership,
        // while still utilizing shared EGL resources.
        final AbstractGraphicsScreen aScreen = screen.getGraphicsScreen();
        final EGLGraphicsDevice aDevice = (EGLGraphicsDevice) aScreen.getDevice();
        final EGLGraphicsDevice eglDevice = EGLDisplayUtil.eglCreateEGLGraphicsDevice(aDevice.getNativeDisplayID(), aDevice.getConnection(), aDevice.getUnitID());
        eglDevice.open();
        final DefaultGraphicsScreen eglScreen = new DefaultGraphicsScreen(eglDevice, aScreen.getIndex());

        final AbstractGraphicsConfiguration cfg = GraphicsConfigurationFactory.getFactory(getScreen().getDisplay().getGraphicsDevice(), capsRequested).chooseGraphicsConfiguration(
                capsRequested, capsRequested, capabilitiesChooser, eglScreen, VisualIDHolder.VID_UNDEFINED);
        if (null == cfg) {
            throw new NativeWindowException("Error choosing GraphicsConfiguration creating window: "+this);
        }
        final Capabilities chosenCaps = (Capabilities) cfg.getChosenCapabilities();
        // FIXME: Pass along opaque flag, since EGL doesn't determine it
        if(capsRequested.isBackgroundOpaque() != chosenCaps.isBackgroundOpaque()) {
            chosenCaps.setBackgroundOpaque(capsRequested.isBackgroundOpaque());
        }
        setGraphicsConfiguration(cfg);
        // CreateWindow0 will issue position/size changed event if clamped and required
        final RectangleImmutable rect = clampRect(screen, new Rectangle(getX(), getY(), getWidth(), getHeight()));
        nativeWindowHandle = CreateWindow0(display.getBCMHandle(), layer,
                                           rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(),
                                           chosenCaps.isBackgroundOpaque(), chosenCaps.getAlphaBits());
        if (nativeWindowHandle == 0) {
            throw new NativeWindowException("Error creating egl window: "+cfg);
        }
        setWindowHandle(nativeWindowHandle);
        if (0 == getWindowHandle()) {
            throw new NativeWindowException("Error native Window Handle is null");
        }
        windowHandleClose = nativeWindowHandle;

        addWindowListener(linuxEventDeviceTracker);
        addWindowListener(linuxMouseTracker);
        focusChanged(false, true);
    }

    @Override
    protected void closeNativeImpl() {
        final DisplayDriver display = (DisplayDriver) getScreen().getDisplay();
        final EGLGraphicsDevice eglDevice = (EGLGraphicsDevice) getGraphicsConfiguration().getScreen().getDevice();

        removeWindowListener(linuxMouseTracker);
        removeWindowListener(linuxEventDeviceTracker);

        if(0!=windowHandleClose) {
            CloseWindow0(display.getBCMHandle(), windowHandleClose);
        }

        eglDevice.close();

        synchronized( layerSync ) {
            usedLayers.put(layer, false);
            layerCount--;
            layer = -1;
            // System.err.println("XXX.Close capacity "+usedLayers.capacity()+", count "+usedLayers.getBitCount());
        }
    }

    @Override
    protected void requestFocusImpl(final boolean reparented) {
        focusChanged(false, true);
    }

    @Override
    protected boolean reconfigureWindowImpl(final int x, final int y, final int width, final int height, final int flags) {
        final RectangleImmutable rect = clampRect((ScreenDriver) getScreen(), new Rectangle(x, y, width, height));
        // reconfigure0 will issue position/size changed events if required
        reconfigure0(nativeWindowHandle, rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(), flags);
        return true;
    }

    @Override
    protected Point getLocationOnScreenImpl(final int x, final int y) {
        return new Point(x,y);
    }

    @Override
    protected void updateInsetsImpl(final Insets insets) {
        // nop ..
    }

    @Override
    protected final void doMouseEvent(final boolean enqueue, final boolean wait, final short eventType, final int modifiers,
                                      final int x, final int y, final short button, final float[] rotationXYZ, final float rotationScale) {
        if( MouseEvent.EVENT_MOUSE_MOVED == eventType ) {
            final DisplayDriver display = (DisplayDriver) getScreen().getDisplay();
            display.moveActivePointerIcon(x, y);
        }
        super.doMouseEvent(enqueue, wait, eventType, modifiers, x, y, button, rotationXYZ, rotationScale);
    }

    @Override
    protected void setPointerIconImpl(final PointerIconImpl pi) {
        final DisplayDriver display = (DisplayDriver) getScreen().getDisplay();
        display.setPointerIconActive(null != pi ? pi.validatedHandle() : 0, linuxMouseTracker.getLastX(), linuxMouseTracker.getLastY());
    }

    @Override
    protected boolean setPointerVisibleImpl(final boolean pointerVisible) {
        final DisplayDriver display = (DisplayDriver) getScreen().getDisplay();
        display.setActivePointerIconVisible(pointerVisible, linuxMouseTracker.getLastX(), linuxMouseTracker.getLastY());
        return true;
    }

    //----------------------------------------------------------------------
    // Internals only
    //
    private final LinuxMouseTracker linuxMouseTracker;
    private final LinuxEventDeviceTracker linuxEventDeviceTracker;

    protected static native boolean initIDs();
    private        native long CreateWindow0(long bcmDisplay, int layer, int x, int y, int width, int height, boolean opaque, int alphaBits);
    private        native void CloseWindow0(long bcmDisplay, long eglWindowHandle);
    private        native void reconfigure0(long eglWindowHandle, int x, int y, int width, int height, int flags);

    private int    layer;
    private long   nativeWindowHandle;
    private long   windowHandleClose;

    private static int nextLayer = 0;
    private static int layerCount = 0;
    private static final int MAX_LAYERS = 32;
    private static final IntBitfield usedLayers = new IntBitfield(MAX_LAYERS);
    private static final Object layerSync = new Object();
}
