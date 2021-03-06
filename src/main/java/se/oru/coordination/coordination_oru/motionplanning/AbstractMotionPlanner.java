package se.oru.coordination.coordination_oru.motionplanning;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import javax.imageio.ImageIO;

import org.metacsp.multi.spatioTemporal.paths.Pose;
import org.metacsp.multi.spatioTemporal.paths.PoseSteering;

import com.vividsolutions.jts.awt.ShapeWriter;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.util.AffineTransformation;

import se.oru.coordination.coordination_oru.util.Missions;

public abstract class AbstractMotionPlanner {
	
	protected static String TEMP_MAP_DIR = ".tempMaps";
	protected int numObstacles = 0;
	
	protected Pose start = null;
	protected Pose[] goal = null;
	protected String mapFilename = null;
	protected String mapFilenameBAK = null;
	protected double mapResolution = 1.0;
	
	protected PoseSteering[] pathPS = null;

	
	public abstract void setFootprint(Coordinate ... coords);
	
	public void setStart(Pose p) {
		Pose newStart = new Pose(p.getX(),p.getY(),Missions.wrapAngle180(p.getTheta()));
		this.start = newStart;
	}
	
	@Deprecated
	public void setGoal(Pose p) {
		this.setGoals(p);
	}
	
	public void setGoals(Pose ... p) {
		ArrayList<Pose> newGoals = new ArrayList<Pose>();
		for (Pose pose : p) newGoals.add(new Pose(pose.getX(),pose.getY(),Missions.wrapAngle180(pose.getTheta())));
		this.goal = newGoals.toArray(new Pose[newGoals.size()]);
	}
	
	public void setMapFilename(String filename) {
		this.mapFilename = filename;
		this.mapFilenameBAK = filename;
	}

	public void setMapResolution(double res) {
		this.mapResolution = res;
	}
	
	public void addObstacles(Geometry ... geom) {
		if (this.mapFilename == null) throw new Error("Please set a map file first!");
		BufferedImage img = null;
		try {
			img = ImageIO.read(new File(mapFilename)); 
			System.out.println("IMGTYPE: " + img.getType());
			Graphics2D g2 = img.createGraphics();
			ShapeWriter writer = new ShapeWriter();
			g2.setPaint(Color.black);
			for (Geometry g : geom) {
				AffineTransformation at = new AffineTransformation();
				at.scale(1.0/mapResolution, -1.0/mapResolution);
				at.translate(0, img.getHeight());
				Geometry scaledGeom = at.transform(g);
				Shape shape = writer.toShape(scaledGeom);
				System.out.println("Shape: " + shape.getBounds2D());
				g2.fill(shape);
			}
			File outputfile = new File(TEMP_MAP_DIR + File.separator + "tempMap" + (numObstacles++) + ".png");
			ImageIO.write(img, "png", outputfile);
			this.mapFilename = outputfile.getAbsolutePath();
		}
		catch (IOException e) { e.printStackTrace(); }		
	}

	public void addObstacles(Geometry geom, Pose ... poses) {
		if (this.mapFilename == null) throw new Error("Please set a map file first!");
		BufferedImage img = null;
		try {
			img = ImageIO.read(new File(mapFilename)); 
			Graphics2D g2 = img.createGraphics();
			ShapeWriter writer = new ShapeWriter();
			g2.setPaint(Color.black);
			for (Pose pose : poses) {
				AffineTransformation at = new AffineTransformation();
				at.rotate(pose.getTheta());
				at.translate(pose.getX(), pose.getY());
				at.scale(1.0/mapResolution, -1.0/mapResolution);
				at.translate(0, img.getHeight());
				Geometry scaledGeom = at.transform(geom);
				Shape shape = writer.toShape(scaledGeom);
				System.out.println("Shape: " + shape.getBounds2D());
				g2.fill(shape);
			}
			File outputfile = new File(TEMP_MAP_DIR + File.separator + "tempMap" + (numObstacles++) + ".png");
			ImageIO.write(img, "png", outputfile);
			this.mapFilename = outputfile.getAbsolutePath();
		}
		catch (IOException e) { e.printStackTrace(); }
	}

	public void clearObstacles() {
		this.setMapFilename(this.mapFilenameBAK);
	}
	
	public PoseSteering[] getPath() {
		return this.pathPS;
	}
	
	public PoseSteering[] getPathInv() {
		ArrayList<PoseSteering> inv = new ArrayList<PoseSteering>();
		for (PoseSteering ps : this.pathPS) inv.add(ps);
		Collections.reverse(inv);
		return inv.toArray(new PoseSteering[inv.size()]);
	}

	public abstract boolean plan();
	
	public static boolean deleteDir(File dir) {
	    if (dir.isDirectory()) {
	        String[] children = dir.list();
	        for (int i=0; i<children.length; i++) {
	            boolean success = deleteDir(new File(dir, children[i]));
	            if (!success) {
	                return false;
	            }
	        }
	    }
	    return dir.delete();
	}

}
