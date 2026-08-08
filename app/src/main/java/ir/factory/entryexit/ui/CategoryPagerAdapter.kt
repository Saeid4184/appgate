package ir.factory.entryexit.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.ui.fragments.CategoryFragment
import ir.factory.entryexit.ui.fragments.InspectionListFragment
import ir.factory.entryexit.ui.fragments.ItemLogListFragment

/** Backs the 6 tabs (Personnel, Machinery, Visitors, Drivers, Weekly Inspection, Goods
 *  entry/exit) in MainActivity's ViewPager2. The 5th and 6th tabs aren't [PersonType]s — the
 *  inspection tab is a checklist layered on the MACHINERY roster, and the goods tab is its own
 *  independent log — so both are appended after the PersonType-driven tabs rather than folded
 *  into that enum. */
class CategoryPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val tabs = listOf(
        PersonType.PERSONNEL,
        PersonType.MACHINERY,
        PersonType.VISITOR,
        PersonType.DRIVER
    )

    override fun getItemCount(): Int = tabs.size + 2 // +1 inspection tab, +1 goods entry/exit tab

    override fun createFragment(position: Int): Fragment = when (position) {
        in tabs.indices -> CategoryFragment.newInstance(tabs[position])
        tabs.size -> InspectionListFragment()
        else -> ItemLogListFragment()
    }

    fun typeAt(position: Int): PersonType? = tabs.getOrNull(position)

    fun positionOf(type: PersonType): Int = tabs.indexOf(type)
}
