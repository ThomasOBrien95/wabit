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

package ca.sqlpower.wabit.swingui.action;

import java.awt.event.ActionEvent;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;

import ca.sqlpower.sqlobject.SQLObjectException;
import ca.sqlpower.swingui.SPSUtils;
import ca.sqlpower.wabit.WabitSession;
import ca.sqlpower.wabit.dao.LoadProjectXMLDAO;
import ca.sqlpower.wabit.swingui.WabitSwingSession;
import ca.sqlpower.wabit.swingui.WabitSwingSessionContext;

/**
 * This action will load in projects from a user selected file to a given
 * context.
 */
public class LoadProjectsAction extends AbstractAction {

	/**
	 * This is the context within Wabit that will have the projects
	 * loaded into.
	 */
	private final WabitSwingSessionContext context;
	
	/**
	 * This session will be used to parent dialogs from this action to.
	 */
	private final WabitSwingSession session;

	public LoadProjectsAction(WabitSwingSession session, WabitSwingSessionContext context) {
		super("Open...", new ImageIcon(LoadProjectsAction.class.getClassLoader().getResource("icons/wabit_load.png")));
		this.session = session;
		this.context = context;
	}

	public void actionPerformed(ActionEvent e) {
		JFileChooser fc = new JFileChooser(session.getCurrentFile());
		fc.setDialogTitle("Select the file to load from.");
		fc.addChoosableFileFilter(SPSUtils.WABIT_FILE_FILTER);
		
		File importFile = null;
		int fcChoice = fc.showOpenDialog(session.getFrame());

		if (fcChoice != JFileChooser.APPROVE_OPTION) {
		    return;
		}
		importFile = fc.getSelectedFile();

		loadFile(importFile, context);
		
	}

	/**
	 * This will load a Wabit project file in a new session in the given context.
	 */
	public static void loadFile(File importFile, WabitSwingSessionContext context) {
		BufferedInputStream in = null;
		try {
			in = new BufferedInputStream(new FileInputStream(importFile));
		} catch (FileNotFoundException e1) {
			throw new RuntimeException(e1);
		}
		List<WabitSession> sessions = loadFile(in, context);
		for (WabitSession session : sessions) {
			((WabitSwingSession)session).setCurrentFile(importFile);
		}
		context.putRecentFileName(importFile.getAbsolutePath());
	}

	/**
	 * This will load a Wabit project file in a new session in the given context
	 * through an input stream. This is slightly different from loading from a
	 * file as no default file to save to will be specified and nothing will be
	 * added to the recent files menu.
	 * 
	 * @return The list of sessions loaded from the input stream.
	 */
	public static List<WabitSession> loadFile(InputStream input, WabitSwingSessionContext context) {
		BufferedInputStream in = new BufferedInputStream(input);
		try {
			LoadProjectXMLDAO projectLoader = new LoadProjectXMLDAO(context, in);
			List<WabitSession> sessions = projectLoader.loadProjects();
			for (WabitSession session : sessions) {
				try {
					((WabitSwingSession)session).buildUI();
				} catch (SQLObjectException e1) {
					throw new RuntimeException(e1);
				}
			}
			return sessions;
		} finally {
			try {
				in.close();
			} catch (IOException e) {
				// squishing exception to not hide other exceptions.
			}
		}
	}

}