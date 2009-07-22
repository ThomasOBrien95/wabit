/*
 * Copyright (c) 2008, SQL Power Group Inc.
 *
 * This file is part of Wabit.
 *
 * Wabit is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Wabit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package ca.sqlpower.wabit;

import ca.sqlpower.sql.DataSourceCollection;

public interface WabitSessionContext {
	
	DataSourceCollection getDataSources();
	
	/**
	 * Adds the given Wabit session to the list of child sessions for this
	 * context. This is normally done by the sessions themselves, so you
	 * shouldn't need to call this method from your own code.
	 */
	void registerChildSession(WabitSession child);

	/**
	 * Removes the given Wabit session from the list of child sessions for this
	 * context. This is normally done by the sessions themselves, so you
	 * shouldn't need to call this method from your own code.
	 */
	void deregisterChildSession(WabitSession child);
		
	/**
	 * returns true if the OS is Mac
	 */
	boolean isMacOSX();
	
	/**
	 * This will create an appropriate session for the current context and will
	 * register the session with the context.
	 */
	WabitSession createSession();
	
	/**
	 * Returns the number of active sessions in the context.
	 */
	int getSessionCount();

	/**
	 * This will attempt to close all of the currently opened sessions and stop
	 * the app. Each session will close independently and if any one session
	 * does not close successfully then the closing operation will stop. Once
	 * all sessions have been properly closed the app will terminate. If not
	 * all sessions are properly closed the app will not terminate.
	 */
	void close();
}