/*======================================================================
 *       _ _     _
 *   ___| (_)___(_) ___  _ __
 *  / _ \ | / __| |/ _ \| '_ \
 * |  __/ | \__ \ | (_) | | | |
 *  \___|_|_|___/_|\___/|_| |_|
 * The Elision Term Rewriter
 * 
 * Copyright (c) 2012 by UT-Battelle, LLC.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * Collection of administrative costs for redistribution of the source code or
 * binary form is allowed. However, collection of a royalty or other fee in excess
 * of good faith amount for cost recovery for such redistribution is prohibited.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER, THE DOE, OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
======================================================================*/

package ornl.elision.gui.trees

import scala.swing.BorderPanel.Position._
import scala.concurrent.ops._
import sys.process._

import java.io._
import java.awt._
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.awt.image.VolatileImage
import javax.swing.SwingWorker

import sage2D._
import sage2D.input._

import ornl.elision.gui._


/**
 * This panel shall display a tree structure showing the rewriting hierarchy 
 * of the last atom string passed to the REPL as input.
 */
class TreeVisPanel(game : GamePanel) extends Level(game, null) with HasCamera {
  /** The panel's camera for panning around and zooming in the visualization */
  val camera = new Camera(0, 0, 640, 480)
  
  /** Keeps track of the mouse's position in world coordinates */
  var mouseWorldPosition : Point2D = new Point2D.Double(0,0)
  
  /** The sprite representing the visualization of the rewrite tree */
  var treeSprite : TreeSprite = elision.sprites.ElisionWelcomeTree //.buildWelcomeTree
  treeSprite.selectNode(treeSprite.root, mainGUI.config.decompDepth) 
    
  /** A SwingWorker thread used for concurrently rendering the panel's image. */
  val renderThread = new TreeVisThread(this,game.timer)
  
  // execute the rendering thread
  renderThread.start
  
  /** The Image containing the latest rendering of the visualization tree. */
  var renderedImage : Image = new BufferedImage(640,480, TreeVisPanel.imageType)


  var timerLock = false
  var isLoading = false
  
  
  /** A right-click menu that appears when you right-click a node. */
  val nodeRClickMenu = new NodeRightClickMenu
  
  /** 
   * We actually run the logic for this level is a thread separate from the EDT. 
   * Here, we just tell the thread that it's safe to run another iteration.
   */
  def logic : Unit = {
    // if we're still processing a previous frame, just return.
    if(!timerLock) {
      timerLock = true
      renderThread.isReady = true
    }
  }
    
  def loadData : Unit = {}
    
  /** 
   * Cleans up the TreeVis panel when we're done with it. 
   * Most importantly, it causes its worker thread to exit after its 
   * current iteration.
   */
  def clean : Unit = {
    renderThread.exitFlag = true
  }
    
    
  /** Draws the tree visualization. */
  def render(g : Graphics2D) : Unit = {
    // render the most recent image produced by our rendering thread.
    g.drawImage(renderedImage, null, null)
    
    // display HUD information
    g.setColor(new Color(0x000000))
    g.drawString("frame rate: " + game.timer.fpsCounter, 10,32)
    g.drawString("camera zoom: " + camera.zoom, 10,52)
  }
    
  /** 
   * test painting function.
   * This draws a grid of red lines centered at the origin spaced 30 pixels apart.
   * It is no longer invoked anywhere. It was used during early development of the GUI to test the SAGE 2D library. 
   * @param g    The graphics context of the component this is being painted to. In this case, it's the TreeVisPanel's context.
   */
/*  def testPaint(g : Graphics2D) : Unit = {
    
    import java.awt.geom.CubicCurve2D
  
    for(i <- -300 to 300 if(i % 30 == 0))  {
      g.setColor(new Color(0xFF9999))
      
      val curve1 = new CubicCurve2D.Double(-300,0,-100,0,100,i,300,i)
      val curve2 = new CubicCurve2D.Double(0,-300,0,-100,i,100,i,300)
      g.draw(curve1)
      g.draw(curve2)
      g.drawLine(-300,i,300,i)
      g.drawLine(i,-300,i,300)
    }
    
    g.setColor(new Color(0xFF0000))
    g.drawLine(-300,0,300,0)
    g.drawLine(0,-300,0,300)
  }
*/
  
  /**
   * Selects a node in the current tree visualization, 
   * does some fancy camera work to make sure that that node stays in the same place onscreen
   * after the graph is re-expanded, and displays that node's information in the atom properties panel.
   * @param clickedNode    The node being selected in our tree.
   */
  
  def selectNode(clickedNode : NodeSprite) : Unit = {
    // don't allow null parameter.
    if(clickedNode == null) {
      return
    }
    
    val clickedNodeScreenPos = camera.worldToScreenCoords(clickedNode.getWorldPosition)
    camera.moveCenter(clickedNodeScreenPos)
    treeSprite.selectNode(clickedNode, mainGUI.config.decompDepth)
    
    mainGUI.sidePanel.propsPanel.textArea.text = clickedNode.properties
    mainGUI.sidePanel.parsePanel.parseStringFormat(clickedNode.term, clickedNode.isComment)
    
    camera.x = clickedNode.worldX
    camera.y = clickedNode.worldY
    
    // right-clicking a node opens a popup menu.
    if(mouse.justRightPressed) {
      nodeRClickMenu.show(game.peer, mouse.position.getX.toInt, mouse.position.getY.toInt, clickedNode)
    }
  }
  
  /** 
   * Performs one iteration through the visualization panel's logic. 
   * It's mostly just processing mouse input for controlling the camera and selecting nodes.
   */
  def threadlogic : Unit = {
    
    if(mouse.justLeftPressed || mouse.justRightPressed) {
        //    requestFocusInWindow
      val clickedNode = treeSprite.detectMouseOver(mouseWorldPosition)
      selectNode(clickedNode)
      camera.startDrag(mouse.position)
    }
    if(mouse.isLeftPressed) {
      camera.drag(mouse.position)
      camera.startDrag(mouse.position)
    }
    if(mouse.wheel == -1)
      camera.zoomAtScreen(10.0/7.0, mouse.position) // camera.zoomAtScreen(1.25, mouse.position)
    if(mouse.wheel == 1)
      camera.zoomAtScreen(7.0/10.0, mouse.position) // camera.zoomAtScreen(0.8, mouse.position)
    
    // update the camera's transform based on its new state.
    camera.pWidth = game.size.width
    camera.pHeight = game.size.height
    camera.updateTransform
    
    // update the mouse's current world coordinates
    mouseWorldPosition = camera.screenToWorldCoords(mouse.position)
  }
  
  // Once the panel is set up, start the timer.
  // The timer will call timerLoop and repaint periodically
  // start()
}

/** */
object TreeVisPanel {
  val imageType = BufferedImage.TYPE_INT_ARGB
}


/** 
 * A worker thread for running the Tree Visualization interaction logic and 
 * rendering, so that the EDT doesn't get tied up during large tree renderings. 
 */
class TreeVisThread(val treeVis : TreeVisPanel, val gt : GameTimer) extends Thread { 
    
    var isReady = false
    var exitFlag = false
    
    override def run : Unit = {
        
      while(!exitFlag) {
        // block until the TreeVisPanel says it should render the next frame.
        while(!isReady) {
            Thread.sleep(1)
            if(exitFlag) return
        }
        try {
          // run the panel's logic before rendering it.
          treeVis.threadlogic
          
          // Create the rendered image.
          val image = render 
          
          // update the TreeVisPanel's image
          treeVis.renderedImage = image
        }
        catch {
          case _ =>
        }
        finally {
          // update the frame rate counter
          gt.updateFrameRateCounter
          
          // we're done, so unlock the TreeVisPanel
          treeVis.timerLock = false
          isReady = false
        }
      } // endwhile
      null
    }
  
  /** Prepares an image containing the current frame rendering for the tree visualization.*/
  def render : Image = {
    val image = treeVis.game.peer.createVolatileImage(treeVis.game.size.width, 
                      treeVis.game.size.height)
    var g = image.createGraphics

    // white background
    g.setColor(new Color(0xffffff))
    g.fillRect(0,0,treeVis.game.size.width, treeVis.game.size.height)
    
    // apply the Camera transform
    g.setTransform(treeVis.camera.getTransform)
    
    //testPaint(g)
    treeVis.treeSprite.camera = treeVis.camera
    treeVis.treeSprite.render(g)
    
    // dispose of the graphics context
    g.dispose
    
    // return the completed image
    image
  }
}






