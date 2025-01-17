/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.micromanager.remote;

import java.awt.*;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mmcorej.TaggedImage;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.api.AcqEngMetadata;
import org.micromanager.acqj.api.DataSink;
import org.micromanager.acqj.api.Acquisition;
import org.micromanager.multiresstorage.MultiResMultipageTiffStorage;
import org.micromanager.multiresstorage.MultiresStorageAPI;
import org.micromanager.multiresstorage.StorageAPI;
import org.micromanager.ndviewer.api.DataSourceInterface;
import org.micromanager.ndviewer.api.ViewerInterface;
import org.micromanager.ndviewer.main.NDViewer;
import org.micromanager.ndviewer.api.ViewerAcquisitionInterface;

/**
 * The class is the glue needed in order for Acquisition engine, viewer, and data storage
 * to be able to be used together, since they are independent libraries that do not know about one
 * another. It implements the Acquisition engine API for a {@link DataSink} interface, dispatching acquired images
 * to viewer and storage as appropriate. It implements viewers {@link DataSourceInterface} interface, so
 * that images in storage can be passed to the viewer to display.
 *
 * @author henrypinkard
 */
public class RemoteViewerStorageAdapter implements DataSourceInterface, DataSink {

   private ExecutorService displayCommunicationExecutor_;

   private volatile ViewerInterface viewer_;
   private volatile RemoteAcquisition acq_;
   private volatile MultiresStorageAPI storage_;
   private CopyOnWriteArrayList<String> channelNames_ = new CopyOnWriteArrayList<String>();

   private final boolean showViewer_, storeData_, xyTiled_;
   private final int tileOverlapX_, tileOverlapY_;
   private String dir_;
   private String name_;
   private Integer maxResLevel_;

   /**
    *
    * @param showViewer create and show a viewer
    * @param dataStorageLocation where should data be saved to disk
    * @param name name for data storage and viewer
    * @param xyTiled true if using XY tiling/multiresolution features
    * @param tileOverlapX X pixel overlap between adjacent tiles if using XY tiling/multiresolution
    * @param tileOverlapY Y pixel overlap between adjacent tiles if using XY tiling/multiresolution
    * @param maxResLevel The maximum resolution level index if using XY tiling/multiresolution
    */
   public RemoteViewerStorageAdapter(boolean showViewer,  String dataStorageLocation,
                                     String name, boolean xyTiled, int tileOverlapX, int tileOverlapY,
                                     Integer maxResLevel) {
      showViewer_ = showViewer;
      storeData_ = dataStorageLocation != null;
      xyTiled_ = xyTiled;
      dir_ = dataStorageLocation;
      name_ = name;
      tileOverlapX_ = tileOverlapX;
      tileOverlapY_ = tileOverlapY;
      maxResLevel_ = maxResLevel;
   }

   public void initialize(Acquisition acq, JSONObject summaryMetadata) {
      acq_ = (RemoteAcquisition) acq;

      if (storeData_) {
         storage_ = new MultiResMultipageTiffStorage(dir_, name_,
                 summaryMetadata, tileOverlapX_, tileOverlapY_,
                 AcqEngMetadata.getWidth(summaryMetadata),
                 AcqEngMetadata.getHeight(summaryMetadata),
                 AcqEngMetadata.isRGB(summaryMetadata) ? 1 :AcqEngMetadata.getBytesPerPixel(summaryMetadata),
                 xyTiled_, maxResLevel_, AcqEngMetadata.isRGB(summaryMetadata));
         name_ = storage_.getUniqueAcqName();
      }

      if (showViewer_) {
         createDisplay(summaryMetadata);
      }
   }

   public StorageAPI getStorage() {
      return storage_;
   }

   private void createDisplay(JSONObject summaryMetadata) {
      //create display
      displayCommunicationExecutor_ = Executors.newSingleThreadExecutor((Runnable r)
              -> new Thread(r, "Image viewer communication thread"));

      viewer_ = new NDViewer(this, (ViewerAcquisitionInterface) acq_,
              summaryMetadata, AcqEngMetadata.getPixelSizeUm(summaryMetadata), AcqEngMetadata.isRGB(summaryMetadata));

      viewer_.setWindowTitle(name_ + (acq_ != null
              ? (acq_.isFinished()? " (Finished)" : " (Running)") : " (Loaded)"));
      //add functions so display knows how to parse time and z infomration from image tags
      viewer_.setReadTimeMetadataFunction((JSONObject tags) -> AcqEngMetadata.getElapsedTimeMs(tags));
      viewer_.setReadZMetadataFunction((JSONObject tags) -> AcqEngMetadata.getZPositionUm(tags));
   }

   public void putImage(final TaggedImage taggedImg) {
      HashMap<String, Integer> axes = AcqEngMetadata.getAxes(taggedImg.tags);
      if (xyTiled_) {
         int row = AcqEngMetadata.getGridRow(taggedImg.tags);
         int col = AcqEngMetadata.getGridCol(taggedImg.tags);
         storage_.putImage(taggedImg, axes, row, col);
      } else {
         storage_.putImage(taggedImg, axes);
      }

      //Check if new viewer to init display settings
      String channelName = AcqEngMetadata.getChannelName(taggedImg.tags);
      boolean newChannel = !channelNames_.contains(channelName);
      if (newChannel) {
         channelNames_.add(channelName);
      }

      if (showViewer_) {
         //put on different thread to not slow down acquisition
         displayCommunicationExecutor_.submit(new Runnable() {
            @Override
            public void run() {
               if (newChannel) {
                  //Insert a preferred color. Make a copy just in case concurrency issues
                  String chName = AcqEngMetadata.getChannelName(taggedImg.tags);
//                  Color c = Color.white; //TODO could add color memory here (or maybe viewer already handles it...)
                  int bitDepth = AcqEngMetadata.getBitDepth(taggedImg.tags);
                  viewer_.setChannelDisplaySettings(chName, null, bitDepth);
               }
               HashMap<String, Integer> axes = AcqEngMetadata.getAxes(taggedImg.tags);
               if (xyTiled_) {
                  //remove this so the viewer doesn't show it
                  axes.remove(AcqEngMetadata.POSITION_AXIS);
               }
               viewer_.newImageArrived(axes, AcqEngMetadata.getChannelName(taggedImg.tags));
            }
         });
      }
   }
  
   ///////// Data source interface for Viewer //////////
   @Override
   public int[] getBounds() {
      return storage_.getImageBounds();
   }

   @Override
   public TaggedImage getImageForDisplay(HashMap<String, Integer> axes, int resolutionindex,
           double xOffset, double yOffset, int imageWidth, int imageHeight) {

      return storage_.getStitchedImage(
              axes, resolutionindex, (int) xOffset, (int) yOffset,
              imageWidth, imageHeight);
   }

   @Override
   public Set<HashMap<String, Integer>> getStoredAxes() {
      return storage_.getAxesSet();
   }

   @Override
   public int getMaxResolutionIndex() {
      return storage_.getNumResLevels() - 1;
   }

   @Override
   public String getDiskLocation() {
      return dir_;
   }
   
   public void close() {
      //anything should be done here? cant think of it now...
   }

   ///////////// Data sink interface required by acq eng /////////////
   @Override
   public void finished() {
      if (storage_ != null) {
         if (!storage_.isFinished()) {
            //Get most up to date display settings
            if (viewer_ != null) {
               JSONObject displaySettings = viewer_.getDisplaySettingsJSON();
               storage_.setDisplaySettings(displaySettings);
            }
            storage_.finishedWriting();
         }
      }
      
      if (showViewer_) {
         viewer_.setWindowTitle(name_ + " (Finished)");
         displayCommunicationExecutor_.shutdown();
      }   
   }

   @Override
   public boolean anythingAcquired() {
      return acq_.anythingAcquired();
   }

   boolean isXYTiled() {
      return xyTiled_;
   }

   int getOverlapX() {
      return tileOverlapX_;
   }

   int getOverlapY() {
      return tileOverlapY_;
   }
}
