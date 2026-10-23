using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

// Owns only a newly created, suspended process and its descendants. Never attaches a user browser.
public sealed class BudgetProcess : IDisposable
{
    [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
        public long ProcessTime, JobTime; public uint Flags;
        public UIntPtr MinWorkingSet, MaxWorkingSet; public uint ActiveProcessLimit;
        public UIntPtr Affinity; public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] struct IoCounters {
        public ulong ReadOps, WriteOps, OtherOps, ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)] struct Limits {
        public BasicLimits Basic; public IoCounters Io;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct Startup {
        public uint Size; public string Reserved, Desktop, Title;
        public uint X,Y,XSize,YSize,XChars,YChars,Fill,Flags;
        public ushort Show, ReservedSize; public IntPtr Reserved2, In, Out, Error;
    }
    [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
        public IntPtr Process, Thread; public uint Id, ThreadId;
    }
    [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr security,string name);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int kind,ref Limits info,uint length);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job,int kind,out Limits info,uint length,out uint returned);
    [DllImport("kernel32.dll", SetLastError=true, EntryPoint="QueryInformationJobObject")] static extern bool QueryIds(IntPtr job,int kind,IntPtr info,uint length,out uint returned);
    [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)] static extern bool CreateProcess(string app,StringBuilder command,IntPtr processSecurity,IntPtr threadSecurity,bool inherit,uint flags,IntPtr environment,string directory,ref Startup startup,out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateProcess(IntPtr process,uint code);
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access,bool inherit,uint pid);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool member);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetProcessAffinityMask(IntPtr process,out UIntPtr mask,out UIntPtr system);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetExitCodeProcess(IntPtr process,out uint code);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle,uint timeout);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    IntPtr job, process; readonly ulong mask, memory; readonly uint root;
    const uint RequiredFlags = 0x2210; // AFFINITY | JOB_MEMORY | KILL_ON_JOB_CLOSE, no breakaway.
    static void Check(bool value) { if(!value) throw new Win32Exception(Marshal.GetLastWin32Error()); }
    BudgetProcess(IntPtr ownedJob,IntPtr ownedProcess,uint id,ulong cpu,ulong bytes) { job=ownedJob; process=ownedProcess; root=id; mask=cpu; memory=bytes; }
    public static BudgetProcess Start(string command,string directory,ulong cpuMask,ulong memoryBytes) {
        IntPtr ownedJob=CreateJobObject(IntPtr.Zero,null); Check(ownedJob!=IntPtr.Zero);
        ProcessInfo info=new ProcessInfo(); bool assigned=false;
        try {
            var limits=new Limits(); limits.Basic.Flags=RequiredFlags; limits.Basic.Affinity=(UIntPtr)cpuMask; limits.JobMemory=(UIntPtr)memoryBytes;
            Check(SetInformationJobObject(ownedJob,9,ref limits,(uint)Marshal.SizeOf<Limits>()));
            var startup=new Startup(); startup.Size=(uint)Marshal.SizeOf<Startup>(); startup.Flags=1; startup.Show=0;
            Check(CreateProcess(null,new StringBuilder(command),IntPtr.Zero,IntPtr.Zero,false,0x08000004,IntPtr.Zero,directory,ref startup,out info));
            Check(AssignProcessToJobObject(ownedJob,info.Process)); assigned=true;
            if(ResumeThread(info.Thread)==UInt32.MaxValue)throw new Win32Exception(Marshal.GetLastWin32Error());
            CloseHandle(info.Thread); info.Thread=IntPtr.Zero;
            return new BudgetProcess(ownedJob,info.Process,info.Id,cpuMask,memoryBytes);
        } catch {
            if(info.Process!=IntPtr.Zero&&!assigned)TerminateProcess(info.Process,1);
            if(info.Thread!=IntPtr.Zero)CloseHandle(info.Thread);
            CloseHandle(ownedJob); if(info.Process!=IntPtr.Zero)CloseHandle(info.Process); throw;
        }
    }
    public bool HasExited { get { return WaitForSingleObject(process,0)==0; } }
    public uint ExitCode { get { uint code; Check(GetExitCodeProcess(process,out code)); return code; } }
    public object Snapshot() {
        Limits limits; uint returned; Check(QueryInformationJobObject(job,9,out limits,(uint)Marshal.SizeOf<Limits>(),out returned));
        var members=new List<object>(); bool verified=limits.Basic.Flags==RequiredFlags&&limits.Basic.Affinity.ToUInt64()==mask&&limits.JobMemory.ToUInt64()==memory;
        int bytes=8192; IntPtr buffer=Marshal.AllocHGlobal(bytes);
        try {
            while(!QueryIds(job,3,buffer,(uint)bytes,out returned)) {
                if(Marshal.GetLastWin32Error()!=234||bytes>=1048576)throw new Win32Exception(Marshal.GetLastWin32Error());
                Marshal.FreeHGlobal(buffer); bytes*=2; buffer=Marshal.AllocHGlobal(bytes);
            }
            int count=Marshal.ReadInt32(buffer,4);
            for(int i=0;i<count;i++) {
                uint pid=(uint)Marshal.ReadIntPtr(buffer,8+i*IntPtr.Size).ToInt64();
                IntPtr handle=OpenProcess(0x1000,false,pid);
                if(handle==IntPtr.Zero) { int error=Marshal.GetLastWin32Error(); if(error==87)continue; throw new Win32Exception(error); }
                try {
                    bool belongs; UIntPtr affinity,system;
                    Check(IsProcessInJob(handle,job,out belongs));
                    if(!GetProcessAffinityMask(handle,out affinity,out system)) {
                        int error=Marshal.GetLastWin32Error();uint code;
                        if(GetExitCodeProcess(handle,out code)&&code!=259)continue;
                        throw new Win32Exception(error);
                    }
                    ulong actual=affinity.ToUInt64(); bool valid=belongs&&actual!=0&&(actual&~mask)==0; verified&=valid;
                    string name="exited"; long started=0;
                    try {using(var item=Process.GetProcessById((int)pid)){name=item.ProcessName; started=item.StartTime.ToUniversalTime().Ticks;}} catch(ArgumentException){} catch(Win32Exception) { uint code; if(!GetExitCodeProcess(handle,out code)||code==259)throw; }
                    members.Add(new {pid=pid,name=name,startedTicks=started,inJob=belongs,affinity=actual,withinBudget=valid});
                } finally {CloseHandle(handle);}
            }
        } finally {Marshal.FreeHGlobal(buffer);}
        return new { at=DateTime.UtcNow, rootPid=root, flags=limits.Basic.Flags, cpuMask=mask, jobMemoryLimitBytes=memory, peakJobCommittedBytes=limits.PeakJobMemory.ToUInt64(), verified=verified, members=members };
    }
    public void Dispose() { if(job!=IntPtr.Zero){CloseHandle(job);job=IntPtr.Zero;} if(process!=IntPtr.Zero){CloseHandle(process);process=IntPtr.Zero;} }
}
