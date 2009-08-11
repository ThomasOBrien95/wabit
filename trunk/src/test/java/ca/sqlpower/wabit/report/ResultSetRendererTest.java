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

package ca.sqlpower.wabit.report;

import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.sqlpower.sql.JDBCDataSource;
import ca.sqlpower.sql.PlDotIni;
import ca.sqlpower.sqlobject.SQLDatabase;
import ca.sqlpower.sqlobject.SQLDatabaseMapping;
import ca.sqlpower.wabit.AbstractWabitObjectTest;
import ca.sqlpower.wabit.QueryCache;
import ca.sqlpower.wabit.WabitObject;
import ca.sqlpower.wabit.report.ColumnInfo.GroupAndBreak;
import ca.sqlpower.wabit.report.resultset.ResultSetCell;

import com.lowagie.text.Section;

public class ResultSetRendererTest extends AbstractWabitObjectTest {

    private ResultSetRenderer renderer;
    
    private SQLDatabase db;
    
    private Graphics graphics;
    
    private SQLDatabaseMapping stubMapping;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        stubMapping = new SQLDatabaseMapping() {
            public SQLDatabase getDatabase(JDBCDataSource ds) {
                return db;
            }
        };
        
        PlDotIni plini = new PlDotIni();
        plini.read(new File("src/test/java/pl.regression.ini"));
        JDBCDataSource ds = plini.getDataSource("regression_test", JDBCDataSource.class);
        db = new SQLDatabase(ds);
        renderer = new ResultSetRenderer(new QueryCache(stubMapping));
        
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        graphics = image.getGraphics();
    }
    
    @Override
    protected void tearDown() throws Exception {
        graphics.dispose();
        super.tearDown();
    }
    
    @Override
    public WabitObject getObjectUnderTest() {
        return renderer;
    }
    
    /**
     * This is a test to confirm that subtotalling columns for breaks works.
     */
    public void testSubtotals() throws Exception {
        Connection con = null;
        Statement stmt = null;
        try {
            con = db.getConnection();
            stmt = con.createStatement();
            stmt.execute("Create table subtotal_table (break_col varchar(50), subtotal_values integer)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('a', 10)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('a', 20)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('a', 30)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('b', 12)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('b', 24)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('fib', 1)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('fib', 1)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('fib', 2)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('fib', 3)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('fib', 5)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('fib', 8)");
            stmt.execute("insert into subtotal_table (break_col, subtotal_values) values ('fib', 13)");
        } finally {
            if (stmt != null) stmt.close();
            if (con != null) con.close();
        }
        
        QueryCache cache = new QueryCache(stubMapping);
        cache.setDataSource(db.getDataSource());
        cache.getQuery().defineUserModifiedQuery("select * from subtotal_table");
        
        ContentBox cb = new ContentBox();
        cb.setWidth(100);
        cb.setHeight(200);
        ResultSetRenderer renderer = new ResultSetRenderer(cache);
        renderer.setParent(cb);
        renderer.executeQuery();
        assertEquals(2, renderer.getColumnInfoList().size());
        renderer.getColumnInfoList().get(0).setWillGroupOrBreak(GroupAndBreak.GROUP);
        renderer.getColumnInfoList().get(1).setWillSubtotal(true);
        Font font = GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()[0];
        renderer.setHeaderFont(font);
        renderer.setBodyFont(font);
        renderer.createResultSetLayout((Graphics2D) graphics, cache.getCachedRowSet());
        List<List<ResultSetCell>> layoutCells = renderer.findCells();
        
        boolean foundATotal = false;
        boolean foundBTotal = false;
        boolean foundFibTotal = false;
        for (List<ResultSetCell> cells : layoutCells) {
            for (ResultSetCell cell : cells) {
                if (cell.getText().equals("60")) {
                    foundATotal = true;
                } else if (cell.getText().equals("36")) {
                    foundBTotal = true;
                } else if (cell.getText().equals("33")) {
                    foundFibTotal = true;
                }
            }
        }
        if (!foundATotal) {
            fail("Could not find the correct subtotal cell for the A column. The cell should contain the value 60");
        } 
        if (!foundBTotal) {
            fail("Could not find the correct subtotal cell for the B column. The cell should contain the value 36");
        } 
        if (!foundFibTotal) {
            fail("Could not find the correct subtotal cell for the Fib column. The cell should contain the value 33");
        }
        
        con = null;
        stmt = null;
        try {
            con = db.getConnection();
            stmt = con.createStatement();
            stmt.execute("drop table subtotal_table");
        } finally {
            if (stmt != null) stmt.close();
            if (con != null) con.close();
        }
        
    }

}