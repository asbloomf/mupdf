package com.artifex.mupdfdemo;

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.view.View;
import android.widget.ListView;

enum Purpose {
	PickPDF,
	PickKeyFile
}

public class ChoosePDFActivity extends ListActivity {
	static public final String PICK_KEY_FILE = "com.artifex.mupdfdemo.PICK_KEY_FILE";
	static private Map<String, Integer> mPositions = new HashMap<String, Integer>();
	private File []      mFiles;
	private Handler	     mHandler;
	private Runnable     mUpdateFiles;
	private ChoosePDFAdapter adapter;
	private Purpose      mPurpose;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		mPurpose = PICK_KEY_FILE.equals(getIntent().getAction()) ? Purpose.PickKeyFile : Purpose.PickPDF;

		String storageState = Environment.getExternalStorageState();

		if (!Environment.MEDIA_MOUNTED.equals(storageState)
				&& !Environment.MEDIA_MOUNTED_READ_ONLY.equals(storageState))
		{
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle(R.string.no_media_warning);
			builder.setMessage(R.string.no_media_hint);
			AlertDialog alert = builder.create();
			alert.setButton(AlertDialog.BUTTON_POSITIVE,getString(R.string.dismiss),
					new OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							finish();
						}
					});
			alert.show();
			return;
		}

		// Create a list adapter...
		adapter = new ChoosePDFAdapter(getLayoutInflater());
		setListAdapter(adapter);

		// ...that is updated dynamically when files are scanned
		mHandler = new Handler();
		mUpdateFiles = new Runnable() {
			private void addFolders(File dir) {
				File[] dirs = dir.listFiles(new FileFilter() {

					public boolean accept(File file) {
						return file.isDirectory();
					}
				});
				if (dirs == null)
					dirs = new File[0];

				Arrays.sort(dirs, new Comparator<File>() {
					public int compare(File arg0, File arg1) {
						return arg0.getName().compareToIgnoreCase(arg1.getName());
					}
				});

				for (File f : dirs) {
					addFiles(f);
					addFolders(f);
				}
			}
			private void addFiles(File dir) {
				File[] files = dir.listFiles(new FileFilter() {

					public boolean accept(File file) {
						if (file.isDirectory())
							return false;
						String fname = file.getName().toLowerCase();
						switch (mPurpose) {
							case PickPDF:
								if (fname.endsWith(".pdf"))
									return true;
								if (fname.endsWith(".xps"))
									return true;
								if (fname.endsWith(".cbz"))
									return true;
								if (fname.endsWith(".epub"))
									return true;
								if (fname.endsWith(".fb2"))
									return true;
								if (fname.endsWith(".png"))
									return true;
								if (fname.endsWith(".jpe"))
									return true;
								if (fname.endsWith(".jpeg"))
									return true;
								if (fname.endsWith(".jpg"))
									return true;
								if (fname.endsWith(".jfif"))
									return true;
								if (fname.endsWith(".jfif-tbnl"))
									return true;
								if (fname.endsWith(".tif"))
									return true;
								if (fname.endsWith(".tiff"))
									return true;
								return false;
							case PickKeyFile:
								if (fname.endsWith(".pfx"))
									return true;
								return false;
							default:
								return false;
						}
					}
				});
				if (files == null || files.length == 0)
					return;

				Arrays.sort(files, new Comparator<File>() {
					public int compare(File arg0, File arg1) {
						return arg0.getName().compareToIgnoreCase(arg1.getName());
					}
				});

				adapter.add(new ChoosePDFItem(ChoosePDFItem.Type.DIR, dir.getAbsolutePath()));
				for (File f : files)
					adapter.add(new ChoosePDFItem(ChoosePDFItem.Type.DOC, f.getName()));
			}
			public void run() {
				Resources res = getResources();
				String appName = res.getString(R.string.app_name);
				String version = res.getString(R.string.version);
				String title = res.getString(R.string.picker_title_App_Ver_Dir);
				setTitle(String.format(title, appName, version, ""));

				File root = new File("/");
				addFiles(root);
				addFolders(root);

				adapter.clear();

				lastPosition();
			}
		};

		// Start initial file scan...
		mHandler.post(mUpdateFiles);

		// ...and observe the directory and scan files upon changes.
		FileObserver observer = new FileObserver("/", FileObserver.CREATE | FileObserver.DELETE) {
			public void onEvent(int event, String path) {
				mHandler.post(mUpdateFiles);
			}
		};
		observer.startWatching();
	}

	private void lastPosition() {
		String p = "/";
		if (mPositions.containsKey(p))
			getListView().setSelection(mPositions.get(p));
	}

	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
		super.onListItemClick(l, v, position, id);

		mPositions.put("/", getListView().getFirstVisiblePosition());

		ChoosePDFItem item = (ChoosePDFItem)adapter.getItem(position);

		if(item.type != ChoosePDFItem.Type.DOC) return;

		Uri uri = Uri.fromFile(new File(item.fullPath));
		Intent intent = new Intent(this,MuPDFActivity.class);
		intent.setAction(Intent.ACTION_VIEW);
		intent.setData(uri);
		switch (mPurpose) {
		case PickPDF:
			// Start an activity to display the PDF file
			startActivity(intent);
			break;
		case PickKeyFile:
			// Return the uri to the caller
			setResult(RESULT_OK, intent);
			finish();
			break;
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		mPositions.put("/", getListView().getFirstVisiblePosition());
	}
}
