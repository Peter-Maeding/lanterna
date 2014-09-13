/*
 * This file is part of lanterna (http://code.google.com/p/lanterna/).
 * 
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Copyright (C) 2010-2014 Martin
 */
package com.googlecode.lanterna.gui2;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;

import java.util.*;

/**
 *
 * @author Martin
 */
public class StackedModalWindowManager implements WindowManager {
    
    public static final Hint LOCATION_CENTERED = new Hint();
    public static final Hint LOCATION_CASCADE = new Hint();
    public static final Hint DONT_RESIZE_TO_FIT_SCREEN = new Hint();
    public static final Hint NO_WINDOW_DECORATIONS = new Hint();
    private static final int CASCADE_SHIFT_RIGHT = 2;
    private static final int CASCADE_SHIFT_DOWN = 1;

    private final SortedSet<ManagedWindow> windowStack;
    private final WindowDecorationRenderer windowDecorationRenderer;
    private TerminalPosition nextTopLeftPosition;

    public StackedModalWindowManager() {
        this.windowStack = new TreeSet<ManagedWindow>();
        this.nextTopLeftPosition = new TerminalPosition(CASCADE_SHIFT_RIGHT, CASCADE_SHIFT_DOWN);
        this.windowDecorationRenderer = new DefaultWindowDecorationRenderer();
    }    

    @Override
    public synchronized void addWindow(Window window) {
        if(window == null) {
            throw new IllegalArgumentException("Cannot call addWindow(...) with null window");
        }
        TerminalPosition topLeftPosition;
        if(hasHint(window, LOCATION_CASCADE) || window.getWindowManagerHints().isEmpty()) {
            topLeftPosition = nextTopLeftPosition;
            nextTopLeftPosition = nextTopLeftPosition
                                    .withColumn(nextTopLeftPosition.getColumn() + CASCADE_SHIFT_RIGHT)
                                    .withRow(nextTopLeftPosition.getRow() + CASCADE_SHIFT_DOWN);
        }
        else {
            topLeftPosition = null;
        }
        ManagedWindow managedWindow = new ManagedWindow(
                window,
                topLeftPosition,
                hasHint(window, DONT_RESIZE_TO_FIT_SCREEN),
                windowStack.size());
        windowStack.add(managedWindow);
        if(window instanceof AbstractWindow) {
            ((AbstractWindow)window).setWindowManager(this);
        }
    }

    @Override
    public synchronized void removeWindow(Window window) {
        if(window == null) {
            throw new IllegalArgumentException("Cannot call removeWindow(...) with null window");
        }
        ManagedWindow managedWindow = getManagedWindow(window);
        if(managedWindow == null) {
            throw new IllegalArgumentException("Unknown window passed to removeWindow(...), this window manager doesn't"
                    + " contain " + window);
        }
        windowStack.remove(managedWindow);
    }

    @Override
    public synchronized Collection<Window> getWindows() {
        List<Window> result = new ArrayList<Window>();
        for(ManagedWindow managedWindow: windowStack) {
            result.add(managedWindow.window);
        }
        return result;
    }

    @Override
    public synchronized Window getActiveWindow() {
        if(windowStack.isEmpty()) {
            return null;
        }
        else {
            return windowStack.last().window;
        }
    }

    @Override
    public synchronized boolean handleInput(KeyStroke keyStroke) {
        return !windowStack.isEmpty() && windowStack.last().window.handleInput(keyStroke);

    }

    @Override
    public boolean isInvalid() {
        for(ManagedWindow managedWindow: windowStack) {
            if(managedWindow.window.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public WindowDecorationRenderer getWindowDecorationRenderer(Window window) {
        return windowDecorationRenderer;
    }

    @Override
    public synchronized TerminalPosition getTopLeftPosition(Window window, TerminalSize screenSize) {
        ManagedWindow managedWindow = getManagedWindow(window);
        if(managedWindow == null) {
            throw new IllegalArgumentException("Cannot call getTopLeftPosition of " + window + " on " + toString() +
                    " as it's not managed by this window manager");
        }
        if(managedWindow.topLeftPosition != null) {
            return managedWindow.topLeftPosition;
        }

        //If the stored position was null, then center the window
        TerminalSize size = getSize(window, null, screenSize);
        return new TerminalPosition(
                (screenSize.getColumns() / 2) - (size.getColumns() / 2),
                (screenSize.getRows() / 2) - (size.getRows() / 2));
    }

    @Override
    public synchronized TerminalSize getSize(Window window, TerminalPosition topLeft, TerminalSize screenSize) {
        return getWindowDecorationRenderer(window).getDecoratedSize(window, getUndecoratedSize(window, topLeft, screenSize));
    }

    private TerminalSize getUndecoratedSize(Window window, TerminalPosition topLeft, TerminalSize screenSize) throws IllegalArgumentException {
        ManagedWindow managedWindow = getManagedWindow(window);
        if(managedWindow == null) {
            throw new IllegalArgumentException("Cannot call getTopLeftPosition of " + window + " on " + toString() +
                    " as it's not managed by this window manager");
        }

        TerminalSize preferredSize = window.getPreferredSize();
        if(managedWindow.allowLargerThanScreenSize) {
            return preferredSize;
        }
        if(topLeft == null) {
            //Assume the window can take up the full screen
            return preferredSize
                    .withColumns(Math.min(preferredSize.getColumns(), screenSize.getColumns()))
                    .withRows(Math.min(preferredSize.getRows(), screenSize.getRows()));
        }

        //We can only take up screen size - top left
        return preferredSize
                .withColumns(Math.min(preferredSize.getColumns(), screenSize.getColumns() - topLeft.getColumn()))
                .withRows(Math.min(preferredSize.getRows(), screenSize.getRows() - topLeft.getRow()));
    }

    private ManagedWindow getManagedWindow(Window forWindow) {
        for(ManagedWindow managedWindow: windowStack) {
            if(forWindow == managedWindow.window) {
                return managedWindow;
            }
        }
        return null;
    }

    private boolean hasHint(Window window, Hint hintToFind) {
        for(Hint hint: window.getWindowManagerHints()) {
            if(hint == hintToFind) {
                return true;
            }
        }
        return false;
    }

    private static class ManagedWindow implements Comparable<ManagedWindow>{
        private final Window window;
        private final TerminalPosition topLeftPosition;
        private final boolean allowLargerThanScreenSize;
        private final int ordinal;

        private ManagedWindow(Window window, TerminalPosition topLeftPosition, boolean allowLargerThanScreenSize, int ordinal) {
            this.window = window;
            this.topLeftPosition = topLeftPosition;
            this.allowLargerThanScreenSize = allowLargerThanScreenSize;
            this.ordinal = ordinal;
        }

        @Override
        public int compareTo(ManagedWindow o) {
            return Integer.compare(ordinal, o.ordinal);
        }
    }
}
