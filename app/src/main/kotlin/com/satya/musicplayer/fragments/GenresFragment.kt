package com.satya.musicplayer.fragments

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import com.google.gson.Gson
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.areSystemAnimationsEnabled
import com.simplemobiletools.commons.extensions.beGoneIf
import com.simplemobiletools.commons.extensions.beVisibleIf
import com.simplemobiletools.commons.extensions.hideKeyboard
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.satya.musicplayer.R
import com.satya.musicplayer.activities.SimpleActivity
import com.satya.musicplayer.activities.TracksActivity
import com.satya.musicplayer.adapters.GenresAdapter
import com.satya.musicplayer.databinding.FragmentGenresBinding
import com.satya.musicplayer.dialogs.ChangeSortingDialog
import com.satya.musicplayer.extensions.audioHelper
import com.satya.musicplayer.extensions.config
import com.satya.musicplayer.extensions.mediaScanner
import com.satya.musicplayer.extensions.viewBinding
import com.satya.musicplayer.helpers.GENRE
import com.satya.musicplayer.helpers.TAB_GENRES
import com.satya.musicplayer.models.Genre
import com.satya.musicplayer.models.sortSafely

class GenresFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment(context, attributeSet) {
    private var genres = ArrayList<Genre>()
    private val binding by viewBinding(FragmentGenresBinding::bind)

    override fun setupFragment(activity: BaseSimpleActivity) {
        ensureBackgroundThread {
            val cachedGenres = activity.audioHelper.getAllGenres()
            activity.runOnUiThread {
                gotGenres(activity, cachedGenres)
            }
        }
    }

    private fun gotGenres(activity: BaseSimpleActivity, cachedGenres: ArrayList<Genre>) {
        genres = cachedGenres
        activity.runOnUiThread {
            val scanning = activity.mediaScanner.isScanning()
            binding.genresPlaceholder.text = if (scanning) {
                context.getString(R.string.loading_files)
            } else {
                context.getString(com.simplemobiletools.commons.R.string.no_items_found)
            }

            binding.genresPlaceholder.beVisibleIf(genres.isEmpty())

            val adapter = binding.genresList.adapter
            if (adapter == null) {
                GenresAdapter(activity, genres, binding.genresList) {
                    activity.hideKeyboard()
                    Intent(activity, TracksActivity::class.java).apply {
                        putExtra(GENRE, Gson().toJson(it as Genre))
                        activity.startActivity(this)
                    }
                }.apply {
                    binding.genresList.adapter = this
                }

                if (context.areSystemAnimationsEnabled) {
                    binding.genresList.scheduleLayoutAnimation()
                }
            } else {
                val oldItems = (adapter as GenresAdapter).items
                if (oldItems.sortedBy { it.id }.hashCode() != genres.sortedBy { it.id }.hashCode()) {
                    adapter.updateItems(genres)
                }
            }
        }
    }

    override fun finishActMode() {
        getAdapter()?.finishActMode()
    }

    override fun onSearchQueryChanged(text: String) {
        val filtered = genres.filter { it.title.contains(text, true) }.toMutableList() as ArrayList<Genre>
        getAdapter()?.updateItems(filtered, text)
        binding.genresPlaceholder.beVisibleIf(filtered.isEmpty())
    }

    override fun onSearchClosed() {
        getAdapter()?.updateItems(genres)
        binding.genresPlaceholder.beGoneIf(genres.isNotEmpty())
    }

    override fun onSortOpen(activity: SimpleActivity) {
        ChangeSortingDialog(activity, TAB_GENRES) {
            val adapter = getAdapter() ?: return@ChangeSortingDialog
            genres.sortSafely(activity.config.genreSorting)
            adapter.updateItems(genres, forceUpdate = true)
        }
    }

    override fun setupColors(textColor: Int, adjustedPrimaryColor: Int) {
        binding.genresPlaceholder.setTextColor(textColor)
        binding.genresFastscroller.updateColors(adjustedPrimaryColor)
        getAdapter()?.updateColors(textColor)
    }

    private fun getAdapter() = binding.genresList.adapter as? GenresAdapter
}
