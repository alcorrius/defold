package com.dynamo.cr.tileeditor;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.dialogs.SaveAsDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.part.IPageSite;
import org.eclipse.ui.progress.IProgressService;
import org.eclipse.ui.statushandlers.StatusManager;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;
import org.eclipse.ui.views.properties.IPropertySheetPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dynamo.cr.editor.core.EditorUtil;
import com.dynamo.cr.editor.ui.AbstractDefoldEditor;
import com.dynamo.cr.properties.FormPropertySheetPage;
import com.dynamo.cr.properties.FormPropertySheetViewer;
import com.dynamo.cr.tileeditor.commands.SetBrushCollisionGroup;
import com.dynamo.cr.tileeditor.core.ITileSetView;
import com.dynamo.cr.tileeditor.core.TileSetModel;
import com.dynamo.cr.tileeditor.core.TileSetPresenter;

public class TileSetEditor extends AbstractDefoldEditor implements ITileSetView {

    private static Logger logger = LoggerFactory.getLogger(TileSetEditor.class);
    private IContainer contentRoot;
    private TileSetPresenter presenter;
    private TileSetEditorOutlinePage outlinePage;
    private FormPropertySheetPage propertySheetPage;
    private boolean dirty = false;
    private boolean refreshPropertiesPosted = false;
    // avoids reloading while saving
    private TileSetRenderer renderer;
    // cache collision groups serve to others
    List<String> collisionGroups;
    List<Color> collisionGroupColors;
    private Cursor pencilCursor;
    private Cursor eraserCursor;

    // EditorPart

    @Override
    public void init(IEditorSite site, IEditorInput input)
            throws PartInitException {

        super.init(site, input);

        IFileEditorInput fileEditorInput = (IFileEditorInput) input;
        IFile file = fileEditorInput.getFile();
        this.contentRoot = EditorUtil.findContentRoot(file);
        if (this.contentRoot == null) {
            throw new PartInitException(
                    "Unable to locate content root for project");
        }

        final TileSetModel model = new TileSetModel(this.contentRoot, this.history, this.undoContext);
        this.presenter = new TileSetPresenter(model, this);

        final String undoId = ActionFactory.UNDO.getId();
        final String redoId = ActionFactory.REDO.getId();

        IActionBars actionBars = site.getActionBars();
        actionBars.setGlobalActionHandler(undoId, undoHandler);
        actionBars.setGlobalActionHandler(redoId, redoHandler);

        this.outlinePage = new TileSetEditorOutlinePage(this.presenter) {
            @Override
            public void init(IPageSite pageSite) {
                super.init(pageSite);
                IActionBars actionBars = pageSite.getActionBars();
                actionBars.setGlobalActionHandler(undoId, undoHandler);
                actionBars.setGlobalActionHandler(redoId, redoHandler);
            }
        };
        this.propertySheetPage = new FormPropertySheetPage(contentRoot) {
            @Override
            public void createControl(Composite parent) {
                super.createControl(parent);
                getViewer().setInput(new Object[] {model});
            }

            @Override
            public void setActionBars(IActionBars actionBars) {
                super.setActionBars(actionBars);
                actionBars.setGlobalActionHandler(undoId, undoHandler);
                actionBars.setGlobalActionHandler(redoId, redoHandler);
            }

            @Override
            public void selectionChanged(IWorkbenchPart part,
                    ISelection selection) {
                // Ignore selections for this property view
            }
        };

        this.renderer = new TileSetRenderer(this.presenter);

        IProgressService service = PlatformUI.getWorkbench()
                .getProgressService();
        TileSetLoader loader = new TileSetLoader(file, this.presenter);
        try {
            service.runInUI(service, loader, null);
            if (loader.exception != null) {
                throw new PartInitException(loader.exception.getMessage(),
                        loader.exception);
            }
        } catch (Throwable e) {
            throw new PartInitException(e.getMessage(), e);
        }
    }

    @Override
    public void dispose() {
        super.dispose();

        this.presenter.dispose();
        if (this.renderer != null) {
            this.renderer.dispose();
        }

        if (this.pencilCursor != null)
            this.pencilCursor.dispose();

        if (this.eraserCursor != null)
            this.eraserCursor.dispose();
    }

    @Override
    public void createPartControl(Composite parent) {
        loadCursors();
        parent.setCursor(eraserCursor);

        this.renderer.createControls(parent);

        // This makes sure the context will be active while this component is
        IContextService contextService = (IContextService) getSite()
                .getService(IContextService.class);
        contextService.activateContext(Activator.TILE_SET_CONTEXT_ID);

        // Set the outline as selection provider
        getSite().setSelectionProvider(this.outlinePage);

        this.presenter.refresh();
    }

    @Override
    public void updateActions() {
        super.updateActions();

        // Make sure the state of the command is updated when switching between multiple editor instances
        // Maybe not the best solution, but the only known one so far
        ICommandService commandService = (ICommandService)getSite().getService(ICommandService.class);
        int index = this.getCollisionGroups().indexOf(getBrushCollisionGroup());
        try {
            HandlerUtil.updateRadioState(commandService.getCommand(SetBrushCollisionGroup.COMMAND_ID), new Integer(index).toString());
            commandService.refreshElements(SetBrushCollisionGroup.COMMAND_ID, null);
        } catch (ExecutionException e) {
            logger.error("Error occurred while upating actions", e);
        }
    }

    private void loadCursors() {
        ImageLoader loader = new ImageLoader();
        ImageData[] pencil = loader.load(getClass().getResourceAsStream("/icons/pencil.png"));
        ImageData[] eraser = loader.load(getClass().getResourceAsStream("/icons/draw_eraser.png"));

        Display display = getSite().getShell().getDisplay();
        pencilCursor = new Cursor(display, pencil[0], 0, 15);
        eraserCursor = new Cursor(display, eraser[0], 0, 15);
    }

    public TileSetPresenter getPresenter() {
        return this.presenter;
    }

    public boolean isRenderingEnabled() {
        return this.renderer != null && this.renderer.isEnabled();
    }

    @Override
    public boolean isDirty() {
        return this.dirty;
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        IFileEditorInput input = (IFileEditorInput) getEditorInput();
        IFile file = input.getFile();
        this.inSave = true;
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            this.presenter.save(stream, monitor);
            file.setContents(
                    new ByteArrayInputStream(stream.toByteArray()), false,
                    true, monitor);
        } catch (Throwable e) {
            Status status = new Status(IStatus.ERROR, Activator.PLUGIN_ID, 0,
                    e.getMessage(), null);
            StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
        } finally {
            this.inSave = false;
        }
    }

    @Override
    public void doSaveAs() {
        IFileEditorInput input= (IFileEditorInput) getEditorInput();
        IFile file = input.getFile();
        SaveAsDialog dialog = new SaveAsDialog(getSite().getShell());
        dialog.setOriginalFile(file);
        dialog.create();

        if (dialog.open() == Window.OK) {
            IPath filePath = dialog.getResult();
            if (filePath == null) {
                return;
            }

            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IFile newFile= workspace.getRoot().getFile(filePath);

            try {
                newFile.create(new ByteArrayInputStream(new byte[0]), IFile.FORCE, new NullProgressMonitor());
            } catch (CoreException e) {
                Status status = new Status(IStatus.ERROR, Activator.PLUGIN_ID, 0,
                        e.getMessage(), null);
                StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
                return;
            }
            FileEditorInput newInput = new FileEditorInput(newFile);
            setInput(newInput);
            setPartName(newInput.getName());

            IStatusLineManager lineManager = getEditorSite().getActionBars().getStatusLineManager();
            IProgressMonitor pm = lineManager.getProgressMonitor();
            doSave(pm);
        }
    }

    @Override
    public boolean isSaveAsAllowed() {
        return true;
    }

    @Override
    public void setFocus() {
        this.renderer.setFocus();
    }

    // ITileSetView

    @Override
    public void setImage(BufferedImage image) {
        this.renderer.setImage(image);
    }

    @Override
    public void setTileWidth(int tileWidth) {
        this.renderer.setTileWidth(tileWidth);
    }

    @Override
    public void setTileHeight(int tileHeight) {
        this.renderer.setTileHeight(tileHeight);
    }

    @Override
    public void setTileMargin(int tileMargin) {
        this.renderer.setTileMargin(tileMargin);
    }

    @Override
    public void setTileSpacing(int tileSpacing) {
        this.renderer.setTileSpacing(tileSpacing);
    }

    @Override
    public void setCollision(BufferedImage collision) {
        this.renderer.setCollision(collision);
    }

    @Override
    public void refreshProperties() {
        postRefreshProperties();
    }

    @Override
    public void setCollisionGroups(List<String> collisionGroups, List<Color> colors, String[] selectedCollisionGroups) {
        this.collisionGroups = collisionGroups;
        this.collisionGroupColors = colors;
        this.outlinePage.setInput(collisionGroups, colors, selectedCollisionGroups);
    }

    @Override
    public void setHulls(float[] hullVertices, int[] hullIndices, int[] hullCounts, Color[] hullColors) {
        this.renderer.setHulls(hullVertices, hullIndices, hullCounts, hullColors);
    }

    @Override
    public void setDirty(boolean dirty) {
        this.dirty = dirty;
        firePropertyChange(PROP_DIRTY);
    }

    @Override
    public void setValid(boolean valid) {
        this.renderer.setEnabled(valid);
    }

    @Override
    public Object getAdapter(@SuppressWarnings("rawtypes") Class adapter) {
        if (adapter == IPropertySheetPage.class) {
            return this.propertySheetPage;
        } else if (adapter == IContentOutlinePage.class) {
            return this.outlinePage;
        } else {
            return super.getAdapter(adapter);
        }
    }

    public List<String> getCollisionGroups() {
        return this.collisionGroups;
    }

    public String getBrushCollisionGroup() {
        return this.renderer.getBrushCollisionGroup();
    }

    public void setBrushCollisionGroup(int index) {
        if (index < 0) {
            this.renderer.setBrushCollisionGroup("", Color.white);
            this.renderer.getControl().setCursor(eraserCursor);
        } else if (index < this.collisionGroups.size()) {
            this.renderer.getControl().setCursor(pencilCursor);
            this.renderer.setBrushCollisionGroup(this.collisionGroups.get(index), this.collisionGroupColors.get(index));
        }
    }

    public void frameTileSet() {
        this.renderer.frameTileSet();
    }

    public void resetZoom() {
        this.renderer.resetZoom();
    }

    private void postRefreshProperties() {
        if (!refreshPropertiesPosted) {
            refreshPropertiesPosted = true;

            Display.getDefault().timerExec(100, new Runnable() {

                @Override
                public void run() {
                    refreshPropertiesPosted = false;
                    FormPropertySheetViewer viewer = propertySheetPage.getViewer();
                    if (viewer != null && !viewer.getControl().isDisposed())
                        propertySheetPage.refresh();
                }
            });
        }
    }

    @Override
    protected void doReload(IFile file) {
        IProgressService service = PlatformUI.getWorkbench()
                .getProgressService();
        TileSetLoader loader = new TileSetLoader(file, this.presenter);
        try {
            service.runInUI(service, loader, null);
            if (loader.exception != null) {
                logger.error("Error occurred while reloading", loader.exception);
            }
        } catch (Throwable e) {
            logger.error("Error occurred while reloading", e);
        }
    }

    @Override
    protected void handleResourceChanged(final IResourceChangeEvent event) {
        if (!inSave) {
            Display display= getSite().getShell().getDisplay();
            display.asyncExec(new Runnable() {
                @Override
                public void run() {
                    presenter.handleResourceChanged(event);
                }
            });
        }
    }

}