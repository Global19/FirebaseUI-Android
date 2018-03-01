package com.firebase.ui.firestore;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.util.Log;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// TODO: There is no reason for this to be an OSA
public class FirestoreInfiniteArray<T> extends ObservableSnapshotArray<T> {

    private static final String TAG = "FirestoreInfiniteArray";

    private SnapshotParser<T> mParser;

    private RecyclerView.Adapter mAdapter;
    private Query mForwardQuery;
    private Query mReverseQuery;

    private int mPageSize = 10;

    private List<Page> mPages = new ArrayList<>();

    // TODO: I am not using reverse query at all
    public FirestoreInfiniteArray(Query forwardQuery, Query reverseQuery, SnapshotParser<T> parser) {
        super(parser);

        mParser = parser;
        mForwardQuery = forwardQuery;
        mReverseQuery = reverseQuery;

        Page page = new Page(0, queryAfter(null));
        page.load();

        mPages.add(page);
    }

    public void setAdapter(RecyclerView.Adapter adapter) {
        // TODO: yikes
        mAdapter = adapter;
    }

    public int getPagesLoaded() {
        int count = 0;
        for (Page page : mPages) {
            if (page.getState() == PageState.LOADED) {
                count++;
            }
        }

        return count;
    }

    public void unloadTopPage() {
        // Find first loaded page
        int firstLoaded = findFirstOfState(PageState.LOADED);
        if (firstLoaded != -1) {
            mPages.get(firstLoaded).unload();
            Log.d(TAG, "UNLOADING " + firstLoaded);
        }
    }

    public void unloadBottomPage() {
        int lastLoaded = findLastOfState(PageState.LOADED);
        if (lastLoaded != -1) {
            mPages.get(lastLoaded).unload();
            Log.d(TAG, "UNLOADING " + lastLoaded);
        }
    }

    public void loadPrevPage() {
        if (countState(PageState.LOADING) > 0) {
            return;
        }

        // Load the last UNLOADED page before the middle "core" of LOADED pages
        int firstLoaded = findFirstOfState(PageState.LOADED);
        int lastUnloadedBefore = findLastOfState(PageState.UNLOADED, firstLoaded);

        if (lastUnloadedBefore != -1) {
            Log.d(TAG, "RELOADING " + lastUnloadedBefore);
            mPages.get(lastUnloadedBefore).load();
        }
    }

    public void loadNextPage() {
        if (countState(PageState.LOADING) > 0) {
            return;
        }

        // There are two cases here
        //  1. Need to load a whole new page
        //  2. Need to load an UNLOADED bottom page

        int lastLoaded = findLastOfState(PageState.LOADED);

        if (lastLoaded == mPages.size() - 1) {
            // Case 1: Load a new page at the bottom
            Page lastPage = getLastPage();
            DocumentSnapshot lastSnapshot = lastPage.getLast();

            // Reached the end, no more items to show
            if (lastSnapshot == null) {
                return;
            }

            Query nextQuery = queryAfter(lastSnapshot);
            Log.d(TAG, "LOADING " + mPages.size());

            // Add and start loading
            int nextPageIndex = mPages.size();
            Page nextPage = new Page(nextPageIndex, nextQuery);
            mPages.add(nextPage);
            nextPage.load();
        } else {
            // Case 2: Need to load a previously unloaded page
            // Find the first UNLOADED page after the middle "core" of loaded pages
            int firstUnloadedAfter = findFirstOfState(PageState.UNLOADED, lastLoaded);

            Log.d(TAG, "RELOADING " + firstUnloadedAfter);
            mPages.get(firstUnloadedAfter).load();
        }

    }

    private void onPageLoaded(int index, int size) {
        int itemsBefore = 0;
        for (int i = 0; i < index; i++) {
            itemsBefore += mPages.get(i).size();
        }

        mAdapter.notifyItemRangeInserted(itemsBefore, size);
    }

    private void onPageUnloaded(int index, int size) {
        int itemsBefore = 0;
        for (int i = 0; i < index; i++) {
            itemsBefore += mPages.get(i).size();
        }

        mAdapter.notifyItemRangeRemoved(itemsBefore, size);
    }

    private int findFirstOfState(PageState state) {
        return findFirstOfState(state, 0);
    }

    private int findFirstOfState(PageState state, int startingAt) {
        for (int i = startingAt; i < mPages.size(); i++) {
            Page page = mPages.get(i);
            if (page.getState() == state) {
                return i;
            }
        }

        return -1;
    }

    private int findLastOfState(PageState state) {
        return findLastOfState(state, mPages.size() - 1);
    }

    private int findLastOfState(PageState state, int endingAt) {
        for (int i = endingAt; i >= 0; i--) {
            Page page = mPages.get(i);
            if (page.getState() == state) {
                return i;
            }
        }

        return -1;
    }

    private int countState(PageState state) {
        int count = 0;
        for (Page page : mPages) {
            if (page.getState() == state) {
                count++;
            }
        }

        return count;
    }

    @NonNull
    @Override
    protected List<DocumentSnapshot> getSnapshots() {
        // TODO: How to allow outside clearing?
        Log.w(TAG, "Called getSnapshots() on an infinite array.");
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public DocumentSnapshot getSnapshot(int index) {
        int remaining = index;
        for (Page page : mPages) {
            if (remaining < page.size()) {
                return page.get(remaining);
            }

            remaining -= page.size();
        }

        throw new IllegalArgumentException(
                "Requested non-existent index: " + index + ", size=" + size());
    }

    @NonNull
    @Override
    public T get(int index) {
        return mParser.parseSnapshot(getSnapshot(index));
    }

    @Override
    public int size() {
        int size = 0;

        for (Page page : mPages) {
            size += page.size();
        }

        return size;
    }

    private Page getLastPage() {
        return mPages.get(mPages.size() - 1);
    }

    private Query queryAfter(@Nullable DocumentSnapshot snapshot) {
        if (snapshot == null) {
            return mForwardQuery.limit(mPageSize);
        }

        return mForwardQuery
                .startAfter(snapshot)
                .limit(mPageSize);
    }

    private enum PageState {
        LOADING,
        LOADED,
        UNLOADED
    }

    private class Page implements OnCompleteListener<QuerySnapshot> {

        // TODO: Does the Page really need to track its own index?
        private PageState mState;
        private final int mIndex;
        private final Query mQuery;

        private DocumentSnapshot mFirstInPage;

        private List<DocumentSnapshot> mSnapshots = new ArrayList<>();

        public Page(int index, Query query) {
            mIndex = index;
            mQuery = query;

            mState = PageState.UNLOADED;
        }

        public void load() {
            // TODO: start and end
            mState = PageState.LOADING;
            mQuery.get().addOnCompleteListener(this);
        }

        public void unload() {
            int size = mSnapshots.size();
            mSnapshots.clear();

            onPageUnloaded(mIndex, size);
            mState = PageState.UNLOADED;
        }

        @Override
        public void onComplete(@NonNull Task<QuerySnapshot> task) {
            // TODO: Better error handling
            mState = PageState.LOADED;

            if (!task.isSuccessful()) {
                Log.w(TAG, "Failed to get page", task.getException());
            }

            Log.d(TAG, "LOADED " + mIndex);

            // Add all snapshots
            mSnapshots.addAll(task.getResult().getDocuments());

            // Range insert
            onPageLoaded(mIndex, mSnapshots.size());
        }

        public PageState getState() {
            return mState;
        }

        public DocumentSnapshot get(int index) {
            return mSnapshots.get(index);
        }

        public int size() {
            return mSnapshots.size();
        }

        @Nullable
        public DocumentSnapshot getLast() {
            if (mSnapshots == null || mSnapshots.isEmpty()) {
                return null;
            }
            return mSnapshots.get(mSnapshots.size() - 1);
        }
    }
}
