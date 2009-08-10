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
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import ca.sqlpower.swingui.SPSUtils;
import ca.sqlpower.wabit.WabitSession;
import ca.sqlpower.wabit.dao.OpenWorkspaceXMLDAO;
import ca.sqlpower.wabit.swingui.WabitSwingSession;
import ca.sqlpower.wabit.swingui.WabitSwingSessionContext;
import ca.sqlpower.wabit.swingui.WabitSwingSessionContextImpl;

/**
 * This action will load in workspaces from a user selected file to a given
 * context.
 */
public class OpenWorkspaceAction extends AbstractAction {

	/**
	 * This is the context within Wabit that will have the workspaces
	 * loaded into.
	 */
	private final WabitSwingSessionContext context;
	
	public OpenWorkspaceAction(WabitSwingSessionContext context) {
		super("Open Workspace...", WabitSwingSessionContextImpl.OPEN_WABIT_ICON);
		this.context = context;
	}

	public void actionPerformed(ActionEvent e) {
	    File defaultFile = null;
        if (context.getActiveSession() != null) {
            defaultFile = context.getActiveSwingSession().getCurrentFile();
        }
		JFileChooser fc = new JFileChooser(defaultFile);
		fc.setDialogTitle("Select the file to load from.");
		fc.addChoosableFileFilter(SPSUtils.WABIT_FILE_FILTER);
		
		File importFile = null;
		int fcChoice = fc.showOpenDialog(context.getFrame());

		if (fcChoice != JFileChooser.APPROVE_OPTION) {
		    return;
		}
		importFile = fc.getSelectedFile();

		try {
            loadFile(importFile, context);
        } catch (FileNotFoundException e1) {
            JOptionPane.showMessageDialog(context.getFrame(), "Cannot find file " + importFile.getName() + " to open.",
                    "Cannot Find File", JOptionPane.WARNING_MESSAGE);
        }
		
	}

	/**
	 * This will load a Wabit workspace file in a new session in the given context.
	 */
	public static void loadFile(File importFile, WabitSwingSessionContext context) throws FileNotFoundException {
		BufferedInputStream in = null;
		in = new BufferedInputStream(new FileInputStream(importFile));
		List<WabitSession> loadFile = loadFile(in, context);
		try {
            in.close();
        } catch (IOException e) {
            throw new RuntimeException();
        }
		for (WabitSession session : loadFile) {
		    ((WabitSwingSession) session).setCurrentFile(importFile);
		}
		context.putRecentFileName(importFile.getAbsolutePath());
	}

	/**
	 * This will load a Wabit workspace file in a new session in the given context
	 * through an input stream. This is slightly different from loading from a
	 * file as no default file to save to will be specified and nothing will be
	 * added to the recent files menu.
	 * 
	 * @return The list of sessions loaded from the input stream.
	 */
	public static List<WabitSession> loadFile(InputStream input, WabitSwingSessionContext context) {
		BufferedInputStream in = new BufferedInputStream(input);
		try {
			OpenWorkspaceXMLDAO workspaceLoader = new OpenWorkspaceXMLDAO(context, in);
			List<WabitSession> sessions = workspaceLoader.openWorkspaces();
			context.setEditorPanel();
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
