/*
 * Copyright (c) 2009, SQL Power Group Inc.
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

package ca.sqlpower.wabit.swingui.olap;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.swing.JDialog;
import javax.swing.JFrame;

import org.olap4j.CellSet;
import org.olap4j.OlapConnection;
import org.olap4j.OlapStatement;
import org.olap4j.OlapWrapper;
import org.olap4j.query.RectangularCellSetFormatter;

import ca.sqlpower.sql.PlDotIni;
import ca.sqlpower.sql.SPDataSource;
import ca.sqlpower.swingui.DataEntryPanelBuilder;
import ca.sqlpower.wabit.olap.DataSourceAdapter;
import ca.sqlpower.wabit.olap.Olap4jDataSource;

public class MondrianTest {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.naming.factory.initial", "org.osjava.sj.memory.MemoryContextFactory");
        System.setProperty("org.osjava.sj.jndi.shared", "true");
        Context ctx = new InitialContext();
        
        PlDotIni plIni = new PlDotIni();
        plIni.read(new File(System.getProperty("user.home"), "pl.ini"));
        
        Olap4jDataSource olapDataSource = new Olap4jDataSource();
        Olap4jConnectionPanel dep = new Olap4jConnectionPanel(olapDataSource, plIni);
        JFrame dummy = new JFrame();
        dummy.setVisible(true);
        JDialog d = DataEntryPanelBuilder.createDataEntryPanelDialog(dep, dummy, "Proof of concept", "OK");
        d.setModal(true);
        d.setVisible(true);
        if (olapDataSource.getType() == null) {
            return;
        }
        
        SPDataSource ds = olapDataSource.getDataSource();
        ctx.bind(ds.getName(), new DataSourceAdapter(ds));
        
        Class.forName("mondrian.olap4j.MondrianOlap4jDriver");
        Connection connection =
            DriverManager.getConnection(
                "jdbc:mondrian:"
                    + "DataSource='" + ds.getName() + "';"
                    + "Catalog='" + olapDataSource.getMondrianSchema().toString() + "';"
                    );
        OlapConnection olapConnection = ((OlapWrapper) connection).unwrap(OlapConnection.class);
        OlapStatement statement = olapConnection.createStatement();
        CellSet cellSet =
            statement.executeOlapQuery(
                "SELECT {[Measures].[Unit Sales]} ON 0,\n"
                    + "{[Product].Children} ON 1\n"
                    + "FROM [Sales]");

        RectangularCellSetFormatter f = new RectangularCellSetFormatter(true);
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(System.out));
        f.format(cellSet, pw);
        pw.flush();
    }
}
